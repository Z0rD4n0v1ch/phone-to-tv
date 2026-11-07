package com.example.phonetotv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Трансляция экрана (и по желанию звука) на приставку.
 *  - рукопожатие: имя телефона и PIN; если приставка просит PIN — спрашиваем его на телефоне;
 *  - при повороте пересоздаёт кодер под новый размер (FLAG_RESIZE);
 *  - автоподбор качества: при заторах в сети снижает битрейт, потом возвращает;
 *  - при обрыве связи переподключается сам, а если у приставки сменился IP — находит её поиском;
 *  - принимает команды с пульта ТВ: пауза, перемотка, громкость.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PIN = "pin"
        const val EXTRA_MAX_SIDE = "maxSide"
        const val EXTRA_BIT_RATE = "bitRate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUTO_QUALITY = "autoQuality"
        const val EXTRA_AUDIO = "audio"

        const val PREFS = "settings"
        const val KEY_HOST = "host"

        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1

        /** Если запись в сокет висит дольше — считаем связь потерянной. */
        private const val WRITE_STALL_MS = 5000L
        private const val RETRY_DELAY_MS = 2000L
        private const val MIN_BIT_RATE = 1_000_000

        @Volatile
        var isRunning = false
            private set

        /** Слушатель статуса для экрана приложения (вызывается в главном потоке). */
        var statusListener: ((String) -> Unit)? = null
        var lastStatus: String = "Не подключено"
            private set

        /** Приставка просит PIN: экран приложения показывает окно ввода (главный поток). */
        var pinListener: ((String) -> Unit)? = null
        @Volatile
        var pinRequested: String? = null
            private set
        private val pinQueue = LinkedBlockingQueue<String>()

        fun start(
            context: Context, resultCode: Int, data: Intent, host: String, pin: String,
            quality: Quality, autoQuality: Boolean, audio: Boolean
        ) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PIN, pin)
                .putExtra(EXTRA_MAX_SIDE, quality.maxSide)
                .putExtra(EXTRA_BIT_RATE, quality.bitRate)
                .putExtra(EXTRA_FPS, quality.fps)
                .putExtra(EXTRA_AUTO_QUALITY, autoQuality)
                .putExtra(EXTRA_AUDIO, audio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }

        /** PIN, введённый на телефоне (пустая строка — отмена). */
        fun submitPin(pin: String) {
            pinRequested = null
            pinQueue.offer(pin)
        }

        private fun refreshTile(ctx: Context) {
            try {
                TileService.requestListeningState(ctx, ComponentName(ctx, CastTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private data class VideoSize(val width: Int, val height: Int, val dpi: Int)

    /** Остановить трансляцию с сообщением (без переподключения). */
    private class StopCast(message: String) : Exception(message)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: Thread? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile private var socket: Socket? = null
    @Volatile private var stopping = false
    @Volatile private var host = ""
    @Volatile private var pin = ""
    @Volatile private var quality = Quality(1280, 6_000_000, 30)
    @Volatile private var autoQuality = true
    @Volatile private var audioWanted = false

    /** Размер экрана, под который сейчас настроен кодер (для отслеживания поворота). */
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    private val resizeRequested = AtomicBoolean(false)

    /** Сторожок записи: если write() завис (Wi‑Fi пропал), закрываем сокет. */
    @Volatile private var writeStartedAt = 0L
    private val writeLock = Any()

    // Состояние текущего подключения
    @Volatile private var rttMs = -1
    @Volatile private var currentBitRate = 0
    @Volatile private var blockedNs = 0L
    @Volatile private var audioActive = false

    private val watchdog = object : Runnable {
        override fun run() {
            val started = writeStartedAt
            if (started != 0L && SystemClock.elapsedRealtime() - started > WRITE_STALL_MS) {
                try { socket?.close() } catch (_: Exception) {}
            }
            if (isRunning) mainHandler.postDelayed(this, 1000)
        }
    }

    /** Возможности аппаратного кодера H.264 (запрашиваются один раз). */
    private val encoderCaps: MediaCodecInfo.VideoCapabilities? by lazy {
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            c.release()
            caps
        } catch (_: Exception) {
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCast()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                // На Android 14+ startForeground нужно вызвать ДО getMediaProjection.
                goForeground("Подключение…")

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                quality = Quality(
                    intent.getIntExtra(EXTRA_MAX_SIDE, 1280),
                    intent.getIntExtra(EXTRA_BIT_RATE, 6_000_000),
                    intent.getIntExtra(EXTRA_FPS, 30)
                )
                autoQuality = intent.getBooleanExtra(EXTRA_AUTO_QUALITY, true)
                audioWanted = intent.getBooleanExtra(EXTRA_AUDIO, false)
                if (data == null || host.isBlank()) {
                    finishService("Нет разрешения на захват экрана")
                    return START_NOT_STICKY
                }

                val mpm = getSystemService(MediaProjectionManager::class.java)
                val mp = mpm.getMediaProjection(resultCode, data)
                if (mp == null) {
                    finishService("Не удалось начать захват экрана")
                    return START_NOT_STICKY
                }
                // На Android 14+ callback обязателен до createVirtualDisplay.
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCast()
                    }
                }, mainHandler)
                projection = mp

                isRunning = true
                stopping = false
                pinQueue.clear()
                pinRequested = null
                refreshTile(this)
                registerRotationListener()
                mainHandler.postDelayed(watchdog, 1000)
                worker = thread(name = "cast-encoder") { runCast() }
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Основной цикл: подключение → рукопожатие → трансляция → при обрыве снова ----------

    private fun runCast() {
        var errorText: String? = null
        try {
            while (!stopping) {
                val s = connectWithRetry() ?: break
                socket = s
                val connActive = AtomicBoolean(true)
                var reader: Thread? = null
                var audio: Thread? = null
                try {
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                    val input = DataInputStream(BufferedInputStream(s.getInputStream(), 16 * 1024))
                    resizeRequested.set(false)
                    val size = computeVideoSize()
                    if (!handshake(s, out, input, size)) continue // нужен PIN — переподключаемся с ним

                    rttMs = -1
                    blockedNs = 0L
                    currentBitRate = quality.bitRate
                    reader = thread(name = "cast-reader") { readBackChannel(input) }
                    if (audioWanted) audio = startAudio(out, connActive)
                    stream(out, size)
                } catch (e: StopCast) {
                    errorText = e.message
                    break
                } catch (e: IOException) {
                    if (stopping) break
                    postStatus("Связь потеряна, переподключаюсь…")
                } finally {
                    connActive.set(false)
                    writeStartedAt = 0L
                    try { s.close() } catch (_: Exception) {}
                    socket = null
                    try { audio?.join(1000) } catch (_: Exception) {}
                    try { reader?.join(1000) } catch (_: Exception) {}
                    audioActive = false
                }
            }
        } catch (e: Exception) {
            if (!stopping) errorText = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            mainHandler.post {
                releaseProjection()
                finishService(errorText ?: "Трансляция остановлена")
            }
        }
    }

    /** Подключается к приставке, пока не получится или пока трансляцию не остановят. */
    private fun connectWithRetry(): Socket? {
        var attempt = 0
        while (!stopping) {
            postStatus(if (attempt == 0) "Подключение к $host…" else "Нет связи с $host, попытка ${attempt + 1}…")
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 512 * 1024
                s.connect(InetSocketAddress(host, Protocol.VIDEO_PORT), 3000)
                return s
            } catch (_: IOException) {
            }
            attempt++
            // Каждую третью неудачу ищем приставку: роутер мог выдать ей новый IP.
            if (attempt % 3 == 0) {
                val found = try { Discovery.find() } catch (_: Exception) { null }
                if (found != null && found.ip != host) {
                    host = found.ip
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, host).apply()
                    postStatus("Приставка нашлась по новому адресу: $host")
                    continue
                }
            }
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /**
     * Рукопожатие. true — можно транслировать; false — получен PIN, нужно переподключиться.
     * StopCast — остановить трансляцию с сообщением.
     */
    private fun handshake(s: Socket, out: DataOutputStream, input: DataInputStream, size: VideoSize): Boolean {
        s.soTimeout = 45_000 // на приставке могут спрашивать «Принять трансляцию?»
        timedWrite(out) {
            out.writeInt(Protocol.MAGIC)
            out.writeUTF(Protocol.deviceId(this))
            out.writeUTF(Protocol.deviceName(this))
            out.writeUTF(pin)
            out.writeInt(size.width)
            out.writeInt(size.height)
        }
        postStatus("Жду ответа приставки…")
        val (status, message) = try {
            Pair(input.readInt(), input.readUTF())
        } catch (e: EOFException) {
            // Старая версия на приставке молча закрывает соединение.
            throw StopCast("Приставка не отвечает на новый протокол — обновите «Экран на ТВ» на приставке")
        }
        s.soTimeout = 0
        when (status) {
            Protocol.HS_OK -> return true
            Protocol.HS_NEED_PIN, Protocol.HS_WRONG_PIN -> {
                val text = message.ifEmpty { "Введите PIN с экрана телевизора" }
                postStatus(text)
                requestPin(text)
                val entered = try { pinQueue.poll(3, TimeUnit.MINUTES) } catch (_: InterruptedException) { null }
                if (entered.isNullOrBlank() || stopping) throw StopCast("Подключение отменено: нужен PIN с экрана телевизора")
                pin = entered.trim()
                return false
            }
            Protocol.HS_DECLINED -> throw StopCast(message.ifEmpty { "Приставка отклонила подключение" })
            else -> throw StopCast(message.ifEmpty { "Приставка не приняла подключение" })
        }
    }

    private fun requestPin(text: String) {
        pinRequested = text
        mainHandler.post {
            val l = pinListener
            if (l != null) {
                l(text)
            } else {
                // Экран приложения закрыт — зовём через уведомление.
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification("Нужен PIN: нажмите, чтобы ввести"))
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Обратный канал: эхо пинга и команды с пульта ТВ. */
    private fun readBackChannel(input: DataInputStream) {
        try {
            while (true) {
                val type = input.readInt()
                val value = input.readLong()
                when (type) {
                    Protocol.MSG_PONG -> rttMs = ((System.nanoTime() - value) / 1_000_000).toInt().coerceAtLeast(0)
                    Protocol.MSG_KEY -> mainHandler.post { handleKey(value.toInt()) }
                }
            }
        } catch (_: Exception) {
            // соединение закрыто
        }
    }

    private fun handleKey(key: Int) {
        val am = getSystemService(AudioManager::class.java) ?: return
        fun media(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (key) {
            Protocol.KEY_PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Protocol.KEY_REWIND -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            Protocol.KEY_FORWARD -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            Protocol.KEY_VOLUME_UP ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            Protocol.KEY_VOLUME_DOWN ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    /** Трансляция видео; при повороте — новый размер без разрыва связи. */
    private fun stream(out: DataOutputStream, firstSize: VideoSize) {
        var size = firstSize
        var first = true
        while (!stopping) {
            if (!first) {
                timedWrite(out) {
                    out.writeInt(Protocol.FLAG_RESIZE)
                    out.writeLong(0L)
                    out.writeInt(8)
                    out.writeInt(size.width)
                    out.writeInt(size.height)
                }
            }
            first = false
            encode(out, size)
            if (stopping) break
            resizeRequested.set(false) // сбрасываем до замера, чтобы не пропустить ещё один поворот
            size = computeVideoSize()
        }
    }

    /** Кодирует экран и отправляет пакеты, пока не попросят смену размера, не остановят или не оборвётся связь. */
    private fun encode(out: DataOutputStream, size: VideoSize) {
        val q = quality
        val caps = encoderCaps
        val target = caps?.bitrateRange?.clamp(q.bitRate) ?: q.bitRate
        val startRate = minOf(currentBitRate.takeIf { it > 0 } ?: target, target)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, startRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, q.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Если экран неподвижен — повторять кадр, чтобы ТВ не «зависал» и связь не считалась мёртвой.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val inputSurface: android.view.Surface = try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            surface
        } catch (e: Exception) {
            codec.release()
            throw StopCast("Кодер не поддерживает ${size.width}×${size.height} при ${q.fps} к/с — выберите качество ниже")
        }
        currentBitRate = startRate

        try {
            attachSurface(inputSurface, size)
            postStreamingStatus(size)

            val info = MediaCodec.BufferInfo()
            var buffer = ByteArray(256 * 1024)
            var lastTick = SystemClock.elapsedRealtime()
            var goodSeconds = 0
            while (!stopping && !resizeRequested.get()) {
                // Раз в секунду: пинг, статистика для ТВ и автоподбор качества.
                val now = SystemClock.elapsedRealtime()
                if (now - lastTick >= 1000) {
                    lastTick = now
                    goodSeconds = adaptBitRate(codec, target, goodSeconds, size)
                    sendService(out)
                }

                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue

                val outBuf = codec.getOutputBuffer(index)
                if (outBuf != null && info.size > 0) {
                    if (buffer.size < info.size) buffer = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(buffer, 0, info.size)

                    var flags = 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) flags = flags or Protocol.FLAG_CONFIG
                    if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) flags = flags or Protocol.FLAG_KEY_FRAME

                    val len = info.size
                    val pts = info.presentationTimeUs
                    try {
                        val t0 = System.nanoTime()
                        timedWrite(out) {
                            out.writeInt(flags)
                            out.writeLong(pts)
                            out.writeInt(len)
                            out.write(buffer, 0, len)
                        }
                        blockedNs += System.nanoTime() - t0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                } else {
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            try { virtualDisplay?.surface = null } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { inputSurface.release() } catch (_: Exception) {}
        }
    }

    /**
     * Автоподбор качества: если запись в сеть тормозит или задержка растёт — битрейт ниже;
     * если несколько секунд всё гладко — постепенно возвращаем выбранный.
     */
    private fun adaptBitRate(codec: MediaCodec, target: Int, goodSecondsIn: Int, size: VideoSize): Int {
        val blockedMs = blockedNs / 1_000_000
        blockedNs = 0L
        if (!autoQuality) return 0
        val rtt = rttMs
        var good = goodSecondsIn
        var newRate = currentBitRate
        when {
            blockedMs > 250 || rtt > 250 -> {
                newRate = max(MIN_BIT_RATE, (currentBitRate * 0.7).toInt())
                good = 0
            }
            blockedMs < 40 && rtt in 0..80 -> {
                good++
                if (good >= 4 && currentBitRate < target) {
                    newRate = minOf(target, (currentBitRate * 1.2).toInt())
                    good = 0
                }
            }
            else -> good = 0
        }
        if (newRate != currentBitRate) {
            try {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newRate) })
                currentBitRate = newRate
                postStreamingStatus(size)
            } catch (_: Exception) {
            }
        }
        return good
    }

    /** Пинг (для замера задержки) и статистика для индикатора на ТВ. */
    private fun sendService(out: DataOutputStream) {
        timedWrite(out) {
            out.writeInt(Protocol.FLAG_PING)
            out.writeLong(0L)
            out.writeInt(8)
            out.writeLong(System.nanoTime())
            out.writeInt(Protocol.FLAG_STATS)
            out.writeLong(0L)
            out.writeInt(16)
            out.writeInt(currentBitRate)
            out.writeInt(rttMs)
            out.writeInt(quality.fps)
            out.writeInt(if (audioActive) 1 else 0)
        }
    }

    private fun postStreamingStatus(size: VideoSize) {
        val mbit = String.format(java.util.Locale.US, "%.1f", currentBitRate / 1_000_000.0).replace('.', ',')
        val auto = if (autoQuality && currentBitRate < quality.bitRate) " (снижено: слабая сеть)" else ""
        val sound = if (audioActive) " · звук на ТВ" else ""
        postStatus("Трансляция на $host · ${size.width}×${size.height} · ${quality.fps} к/с · $mbit Мбит/с$auto$sound")
    }

    /** Запись под общим замком (видео, звук и служебные пакеты пишут из разных потоков). */
    private inline fun timedWrite(out: DataOutputStream, block: () -> Unit) {
        synchronized(writeLock) {
            writeStartedAt = SystemClock.elapsedRealtime()
            try {
                block()
                out.flush()
            } finally {
                writeStartedAt = 0L
            }
        }
    }

    // ---------- Звук (Android 10+) ----------

    /** Захват звука приложений телефона и отправка на ТВ. null — звук недоступен. */
    @SuppressLint("MissingPermission")
    private fun startAudio(out: DataOutputStream, connActive: AtomicBoolean): Thread? {
        if (Build.VERSION.SDK_INT < 29) return null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val mp = projection ?: return null
        val record = try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Protocol.AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                Protocol.AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuf, 3840 * 4))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (_: Exception) {
            null
        } ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return thread(name = "cast-audio") {
            try {
                record.startRecording()
                audioActive = true
                val chunk = ByteArray(1920) // 10 мс: 480 кадров × 2 канала × 2 байта
                while (connActive.get() && !stopping) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var silent = true
                    for (i in 0 until n) if (chunk[i].toInt() != 0) { silent = false; break }
                    if (silent) continue // тишину не гоняем по сети
                    timedWrite(out) {
                        out.writeInt(Protocol.FLAG_AUDIO)
                        out.writeLong(SystemClock.elapsedRealtimeNanos() / 1000)
                        out.writeInt(n)
                        out.write(chunk, 0, n)
                    }
                }
            } catch (_: Exception) {
                // соединение закрыто или звук отобрали
            } finally {
                audioActive = false
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }
    }

    /**
     * Направляет экран в поверхность кодера. VirtualDisplay создаётся один раз за трансляцию
     * (на Android 14+ повторно создать его из того же MediaProjection нельзя), дальше только resize/setSurface.
     */
    private fun attachSurface(surface: android.view.Surface, size: VideoSize) {
        val vd = virtualDisplay
        if (vd == null) {
            val mp = projection ?: throw IllegalStateException("Захват экрана остановлен")
            virtualDisplay = mp.createVirtualDisplay(
                "PhoneToTv", size.width, size.height, size.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        } else {
            vd.resize(size.width, size.height, size.dpi)
            vd.surface = surface
        }
    }

    // ---------- Размер видео и поворот ----------

    private fun defaultDisplay(): Display =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private fun realScreen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay().getRealMetrics(dm)
        return dm
    }

    /** Размер видео с сохранением пропорций экрана, кратный 16, который точно поддерживает кодер. */
    private fun computeVideoSize(): VideoSize {
        val dm = realScreen()
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val caps = encoderCaps
        val fps = quality.fps.toDouble()
        var side = quality.maxSide
        while (true) {
            val scale = minOf(1.0, side.toDouble() / max(dm.widthPixels, dm.heightPixels))
            val w = align16((dm.widthPixels * scale).roundToInt())
            val h = align16((dm.heightPixels * scale).roundToInt())
            val ok = caps == null || try { caps.areSizeAndRateSupported(w, h, fps) } catch (_: Exception) { true }
            if (ok || side <= 480) return VideoSize(w, h, dm.densityDpi)
            side = (side * 0.85).toInt()
        }
    }

    private fun align16(v: Int) = max(16, (v / 16) * 16)

    private fun registerRotationListener() {
        val dmgr = getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || !isRunning) return
                val dm = realScreen()
                if (screenW != 0 && (dm.widthPixels != screenW || dm.heightPixels != screenH)) {
                    resizeRequested.set(true)
                }
            }
        }
        dmgr.registerDisplayListener(l, mainHandler)
        displayListener = l
    }

    // ---------- Остановка ----------

    /** Запрос остановки (из уведомления, кнопки, плитки или системы). */
    private fun stopCast() {
        if (stopping) return
        stopping = true
        pinQueue.offer("") // разбудить ожидание PIN
        worker?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        if (worker == null || worker?.isAlive != true) {
            releaseProjection()
            finishService("Трансляция остановлена")
        }
        // Иначе поток сам всё освободит в finally.
    }

    private fun releaseProjection() {
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        val mp = projection
        projection = null
        try { mp?.stop() } catch (_: Exception) {}
    }

    private fun finishService(status: String) {
        isRunning = false
        worker = null
        pinRequested = null
        mainHandler.removeCallbacks(watchdog)
        setStatus(status)
        refreshTile(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ---------- Статус и уведомление ----------

    private fun postStatus(text: String) = mainHandler.post { setStatus(text) }

    private fun setStatus(text: String) {
        lastStatus = text
        statusListener?.invoke(text)
        if (isRunning && pinRequested == null) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            } catch (_: Exception) {
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, SenderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Экран транслируется на ТВ")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Стоп", stopIntent).build())
            .build()
    }

    private fun goForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Трансляция экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopping = true
        pinQueue.offer("")
        worker?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        mainHandler.removeCallbacks(watchdog)
        releaseProjection()
        isRunning = false
        refreshTile(this)
        super.onDestroy()
    }
}
