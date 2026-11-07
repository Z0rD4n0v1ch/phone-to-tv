package com.example.phonetotv

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Захватывает экран через MediaProjection, кодирует в H.264 и отправляет по TCP.
 *  - при повороте телефона пересоздаёт кодер под новый размер и сообщает приставке (FLAG_RESIZE);
 *  - при обрыве связи сам переподключается, а если у приставки сменился IP — находит её поиском.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_MAX_SIDE = "maxSide"
        const val EXTRA_BIT_RATE = "bitRate"
        const val EXTRA_FPS = "fps"

        const val PREFS = "settings"
        const val KEY_HOST = "host"

        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 1

        /** Если запись в сокет висит дольше — считаем связь потерянной. */
        private const val WRITE_STALL_MS = 5000L
        private const val RETRY_DELAY_MS = 2000L

        @Volatile
        var isRunning = false
            private set

        /** Слушатель статуса для экрана приложения (вызывается в главном потоке). */
        var statusListener: ((String) -> Unit)? = null
        var lastStatus: String = "Не подключено"
            private set

        fun start(context: Context, resultCode: Int, data: Intent, host: String, quality: Quality) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_MAX_SIDE, quality.maxSide)
                .putExtra(EXTRA_BIT_RATE, quality.bitRate)
                .putExtra(EXTRA_FPS, quality.fps)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }

    private data class VideoSize(val width: Int, val height: Int, val dpi: Int)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: Thread? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile private var socket: Socket? = null
    @Volatile private var stopping = false
    @Volatile private var host = ""
    @Volatile private var quality = Quality(1280, 6_000_000, 30)

    /** Размер экрана, под который сейчас настроен кодер (для отслеживания поворота). */
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    private val resizeRequested = AtomicBoolean(false)

    /** Сторожок записи: если write() завис (Wi‑Fi пропал), закрываем сокет. */
    @Volatile private var writeStartedAt = 0L

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
                quality = Quality(
                    intent.getIntExtra(EXTRA_MAX_SIDE, 1280),
                    intent.getIntExtra(EXTRA_BIT_RATE, 6_000_000),
                    intent.getIntExtra(EXTRA_FPS, 30)
                )
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
                registerRotationListener()
                mainHandler.postDelayed(watchdog, 1000)
                worker = thread(name = "cast-encoder") { runCast() }
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Основной цикл: подключение → трансляция → при обрыве снова подключение ----------

    private fun runCast() {
        var errorText: String? = null
        try {
            while (!stopping) {
                val s = connectWithRetry() ?: break
                socket = s
                try {
                    stream(s)
                } catch (e: IOException) {
                    if (stopping) break
                    postStatus("Связь потеряна, переподключаюсь…")
                } finally {
                    writeStartedAt = 0L
                    try { s.close() } catch (_: Exception) {}
                    socket = null
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
                val found = try { Discovery.find(attempts = 2) } catch (_: Exception) { null }
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

    /** Одно подключение: заголовок, затем кодирование; при повороте — новый размер без разрыва связи. */
    private fun stream(s: Socket) {
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
        resizeRequested.set(false)
        var size = computeVideoSize()
        timedWrite(out) {
            out.writeInt(Protocol.MAGIC)
            out.writeInt(size.width)
            out.writeInt(size.height)
        }
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
        val bitRate = caps?.bitrateRange?.clamp(q.bitRate) ?: q.bitRate
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
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
            throw IllegalStateException("Кодер не поддерживает ${size.width}×${size.height} при ${q.fps} к/с — выберите качество ниже")
        }

        try {
            attachSurface(inputSurface, size)
            postStatus("Трансляция на $host · ${size.width}×${size.height} · ${q.fps} к/с · ${bitRate / 1_000_000} Мбит/с")

            val info = MediaCodec.BufferInfo()
            var buffer = ByteArray(256 * 1024)
            while (!stopping && !resizeRequested.get()) {
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
                        timedWrite(out) {
                            out.writeInt(flags)
                            out.writeLong(pts)
                            out.writeInt(len)
                            out.write(buffer, 0, len)
                        }
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

    /** Запись с отметкой времени для сторожка; flush внутри. */
    private inline fun timedWrite(out: DataOutputStream, block: () -> Unit) {
        writeStartedAt = SystemClock.elapsedRealtime()
        try {
            block()
            out.flush()
        } finally {
            writeStartedAt = 0L
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

    /** Запрос остановки (из уведомления, кнопки или системы). */
    private fun stopCast() {
        if (stopping) return
        stopping = true
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
        mainHandler.removeCallbacks(watchdog)
        setStatus(status)
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
        if (isRunning) {
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
            Intent(this, SenderActivity::class.java),
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
        worker?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        mainHandler.removeCallbacks(watchdog)
        releaseProjection()
        isRunning = false
        super.onDestroy()
    }
}
