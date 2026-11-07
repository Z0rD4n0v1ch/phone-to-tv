package com.example.phonetotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Режим «Принимать»: экран ожидания (IP, PIN, QR-код, настройки) и показ трансляции.
 *  - PIN при первом подключении телефона; доверенные телефоны дальше подключаются без него;
 *  - если транслирует один телефон, а подключается другой — спрашивает, принять ли;
 *  - звук с телефона, индикатор качества, пульт ТВ управляет телефоном;
 *  - картинка «вписать» или «заполнить экран»; автозапуск при включении приставки.
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private companion object {
        /** Нет данных дольше — соединение считаем мёртвым (телефон шлёт кадры минимум 10 раз в секунду). */
        const val READ_TIMEOUT_MS = 8000
        const val QR_PAGE = "https://z0rd4n0v1ch.github.io/tools-box/tv/"
        const val TV_PREFS = "tv"
        const val KEY_PIN_ON = "pin_on"
        const val KEY_TRUSTED = "trusted"
        const val KEY_FILL = "fill"
        const val KEY_STATS = "stats"
    }

    // ---------- UI ----------
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statsText: TextView
    private lateinit var hintText: TextView
    private lateinit var waiting: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var pinText: TextView
    private lateinit var qrView: ImageView
    private lateinit var updateButton: Button
    private lateinit var autostartButton: Button
    private lateinit var pinButton: Button
    private lateinit var fillButton: Button

    private val prefs by lazy { getSharedPreferences(TV_PREFS, Context.MODE_PRIVATE) }

    // ---------- сеть ----------
    @Volatile private var surface: Surface? = null
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val surfaceLock = Object()

    /** Текущая трансляция (только одна). */
    private val activeLock = Object()
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeThread: Thread? = null
    @Volatile private var activeId: String? = null
    @Volatile private var activeName = ""
    @Volatile private var backOut: DataOutputStream? = null
    private val backExecutor = Executors.newSingleThreadExecutor()

    /** PIN этого запуска приложения. */
    private val sessionPin = String.format(Locale.US, "%04d", SecureRandom().nextInt(10000))
    @Volatile private var waitingNote: String? = null

    // ---------- индикатор ----------
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var framesShown = 0
    @Volatile private var bytesIn = 0L
    @Volatile private var remoteBitRate = 0
    @Volatile private var remoteRtt = -1
    @Volatile private var remoteFps = 0
    @Volatile private var remoteAudio = false
    private var okLongPressed = false

    private val updListener: () -> Unit = { if (isWaitingShown()) renderWaiting() }
    private val updTimer = object : Runnable {
        override fun run() {
            if (activeSocket == null) Updater.check(this@ReceiverActivity, manual = false)
            root.postDelayed(this, 60 * 60 * 1000L)
        }
    }
    private val statsTimer = object : Runnable {
        override fun run() {
            renderStats()
            root.postDelayed(this, 1000)
        }
    }
    private val hideHint = Runnable { hintText.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hideSystemUi()
        Updater.showWhatsNewIfUpdated(this)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))

        statsText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = dp(6).toFloat() }
            visibility = View.GONE
        }
        root.addView(statsText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })

        hintText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(8).toFloat() }
            visibility = View.GONE
        }
        root.addView(hintText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(32)
        })

        // ----- экран ожидания -----
        waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f }
        pinText = TextView(this).apply {
            setTextColor(0xFFFF8A3D.toInt())
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, 0)
        }
        texts.addView(infoText)
        texts.addView(pinText)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        qrView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
        row.addView(qrView, LinearLayout.LayoutParams(dp(220), dp(220)).apply { marginStart = dp(24) })
        waiting.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun tvButton(onClick: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = 15f
            isFocusable = true
            setOnClickListener { onClick() }
        }
        updateButton = tvButton { Updater.install(this) }
        autostartButton = tvButton { toggleAutostart() }
        pinButton = tvButton { prefs.edit().putBoolean(KEY_PIN_ON, !pinOn()).apply(); renderWaiting() }
        fillButton = tvButton { prefs.edit().putBoolean(KEY_FILL, !fillOn()).apply(); renderWaiting() }
        for (b in listOf(updateButton, autostartButton, pinButton, fillButton)) {
            buttons.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        if (!LauncherActivity.isTv(this)) {
            buttons.addView(tvButton { LauncherActivity.chooseMode(this) }.apply { text = "Сменить режим" })
        }
        waiting.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(waiting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // На телефоне/планшете в режиме приёма касание экрана включает индикатор.
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP && !isWaitingShown()) toggleStats()
            true
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        running = true
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("ptv-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        showWaiting()
        Updater.addListener(updListener)
        root.postDelayed(updTimer, 60 * 60 * 1000L)
        root.post(statsTimer)
        thread(name = "video-server") { serverLoop() }
        thread(name = "discovery") { discoveryLoop() }
    }

    override fun onResume() {
        super.onResume()
        Updater.onResume(this)
        // На приставке приложение часто просто сворачивают — проверяем при каждом открытии.
        if (Updater.stage == Updater.Stage.IDLE || Updater.stage == Updater.Stage.UP_TO_DATE) {
            Updater.check(this, manual = false)
        }
        if (isWaitingShown()) renderWaiting()
    }

    override fun onStop() {
        Updater.removeListener(updListener)
        root.removeCallbacks(updTimer)
        root.removeCallbacks(statsTimer)
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
        multicastLock?.release()
        multicastLock = null
        super.onStop()
    }

    override fun onDestroy() {
        backExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- пульт ----------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // На телефоне «Назад» ведёт к выбору режима, на ТВ — обычный выход.
        if (LauncherActivity.isTv(this)) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            LauncherActivity.chooseMode(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isWaitingShown() || activeSocket == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) { okLongPressed = false; event.startTracking() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                remoteKey(Protocol.KEY_PLAY_PAUSE, "⏯ Пауза / воспроизведение"); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                remoteKey(Protocol.KEY_REWIND, "⏪ Назад"); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                remoteKey(Protocol.KEY_FORWARD, "⏩ Вперёд"); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { remoteKey(Protocol.KEY_VOLUME_UP, "🔊 Громкость телефона +"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { remoteKey(Protocol.KEY_VOLUME_DOWN, "🔉 Громкость телефона −"); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { toggleStats(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            okLongPressed = true
            toggleStats()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isWaitingShown() && activeSocket != null &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            if (!okLongPressed && event.isTracking && !event.isCanceled) remoteKey(Protocol.KEY_PLAY_PAUSE, "⏯ Пауза / воспроизведение")
            okLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun remoteKey(key: Int, label: String) {
        showHint(label, 1200)
        sendBack(Protocol.MSG_KEY, key.toLong())
    }

    /** Сообщение телефону по обратному каналу (не из главного потока). */
    private fun sendBack(type: Int, value: Long) {
        val out = backOut ?: return
        try {
            backExecutor.execute {
                try {
                    synchronized(out) {
                        out.writeInt(type)
                        out.writeLong(value)
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun toggleStats() {
        val on = !prefs.getBoolean(KEY_STATS, false)
        prefs.edit().putBoolean(KEY_STATS, on).apply()
        renderStats()
        showHint(if (on) "Индикатор включён" else "Индикатор выключен", 1200)
    }

    private fun showHint(text: String, ms: Long) {
        hintText.text = text
        hintText.visibility = View.VISIBLE
        root.removeCallbacks(hideHint)
        root.postDelayed(hideHint, ms)
    }

    // ---------- сеть: приём подключений ----------

    private fun serverLoop() {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(Protocol.VIDEO_PORT))
                serverSocket = server
                while (running) {
                    val client = try { server.accept() } catch (e: IOException) { break }
                    thread(name = "video-client") { runClient(client) }
                }
            }
        } catch (e: Exception) {
            if (running) runOnUiThread { infoText.text = "Ошибка сервера: ${e.message}\n\nПерезапустите приставку." }
        }
    }

    private fun runClient(client: Socket) {
        var becameActive = false
        try {
            client.tcpNoDelay = true
            client.receiveBufferSize = 1024 * 1024
            client.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 4 * 1024))

            when (input.readInt()) {
                Protocol.MAGIC -> Unit
                Protocol.MAGIC_V2, Protocol.MAGIC_V1 -> {
                    waitingNote = "Телефон со старой версией «Экрана на ТВ» — обновите приложение на телефоне"
                    runOnUiThread { if (isWaitingShown()) renderWaiting() }
                    return
                }
                else -> return // не наш протокол (например, браузер)
            }
            val id = input.readUTF()
            val name = input.readUTF().ifBlank { "Телефон" }
            val pin = input.readUTF()
            val width = input.readInt()
            val height = input.readInt()

            // 1) PIN для незнакомых телефонов
            if (pinOn() && id !in trusted()) {
                if (pin.isEmpty() || pin != sessionPin) {
                    val status = if (pin.isEmpty()) Protocol.HS_NEED_PIN else Protocol.HS_WRONG_PIN
                    val msg = if (pin.isEmpty()) "Введите PIN, который показан на экране телевизора"
                    else "Неверный PIN. Посмотрите PIN на экране телевизора"
                    reply(out, status, msg)
                    waitingNote = "«$name» хочет подключиться — введите на нём PIN"
                    runOnUiThread {
                        if (isWaitingShown()) renderWaiting() else showHint("«$name» хочет подключиться. PIN: $sessionPin", 8000)
                    }
                    return
                }
                trust(id)
            }

            // 2) Уже транслирует другой телефон — спрашиваем
            val busyWith = activeId
            if (activeSocket != null && busyWith != null && busyWith != id) {
                if (!askAccept(name)) {
                    reply(out, Protocol.HS_DECLINED, "Приставка отклонила подключение: сейчас транслирует «$activeName»")
                    return
                }
            }
            reply(out, Protocol.HS_OK, "")
            waitingNote = null

            // 3) Становимся текущей трансляцией (прежнюю — закрываем)
            // Закрываем и дожидаемся прежнюю трансляцию вне замка (её поток сам берёт замок при завершении).
            val (prevSocket, prevThread) = synchronized(activeLock) { Pair(activeSocket, activeThread) }
            try { prevSocket?.close() } catch (_: Exception) {}
            if (prevThread != null && prevThread !== Thread.currentThread()) prevThread.join(3000)
            synchronized(activeLock) {
                activeSocket = client
                activeThread = Thread.currentThread()
                activeId = id
                activeName = name
                backOut = out
                becameActive = true
            }
            stream(client, input, width, height, name)
        } catch (_: Exception) {
            // Телефон отключился или поток повреждён — ждём следующего подключения.
        } finally {
            try { client.close() } catch (_: Exception) {}
            if (becameActive) {
                synchronized(activeLock) {
                    if (activeSocket === client) {
                        activeSocket = null
                        activeThread = null
                        activeId = null
                        backOut = null
                    }
                }
                if (running && activeSocket == null) runOnUiThread { showWaiting() }
            }
        }
    }

    private fun reply(out: DataOutputStream, status: Int, message: String) {
        synchronized(out) {
            out.writeInt(status)
            out.writeUTF(message)
            out.flush()
        }
    }

    /** Окно «Принять трансляцию?» на ТВ. Ждёт ответа до 30 секунд. */
    private fun askAccept(name: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        var dialog: AlertDialog? = null
        runOnUiThread {
            if (isFinishing) { latch.countDown(); return@runOnUiThread }
            dialog = AlertDialog.Builder(this)
                .setTitle("Новое подключение")
                .setMessage("Сейчас транслирует «$activeName».\nПринять трансляцию с «$name»?")
                .setPositiveButton("Принять") { _, _ -> answer.set(true); latch.countDown() }
                .setNegativeButton("Отклонить") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(30, TimeUnit.SECONDS)
        runOnUiThread { try { dialog?.dismiss() } catch (_: Exception) {} }
        return answer.get()
    }

    /** Показ трансляции: видео (с пересозданием декодера при повороте), звук, пинг и статистика. */
    private fun stream(client: Socket, input: DataInputStream, firstW: Int, firstH: Int, name: String) {
        var width = firstW
        var height = firstH
        var buffer = ByteArray(256 * 1024)
        var audio: AudioTrack? = null
        remoteBitRate = 0; remoteRtt = -1; remoteAudio = false; framesShown = 0; bytesIn = 0L
        runOnUiThread {
            showHint("Трансляция с «$name»\nOK — пауза · ◄ ► — перемотка · ▲ ▼ — громкость телефона\nудерживайте OK — индикатор", 6000)
        }
        try {
            while (running && client === activeSocket) {
                checkSize(width, height)
                val outSurface = waitForSurface() ?: return
                videoW = width
                videoH = height
                val w = width
                val h = height
                runOnUiThread { showVideo(w, h) }

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                var resized = false
                try {
                    codec.configure(format, outSurface, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var gotConfig = false

                    while (running) {
                        val flags = input.readInt()
                        val ptsUs = input.readLong()
                        val size = input.readInt()
                        if (size < 0 || size > 16 * 1024 * 1024) throw IOException("Неверный размер пакета")
                        if (buffer.size < size) buffer = ByteArray(size)
                        input.readFully(buffer, 0, size)
                        bytesIn += size + 16

                        when {
                            (flags and Protocol.FLAG_RESIZE) != 0 -> {
                                if (size < 8) throw IOException("Неверный пакет смены размера")
                                val bb = ByteBuffer.wrap(buffer, 0, 8)
                                width = bb.int
                                height = bb.int
                                resized = true
                            }
                            (flags and Protocol.FLAG_PING) != 0 -> {
                                if (size >= 8) sendBack(Protocol.MSG_PONG, ByteBuffer.wrap(buffer, 0, 8).long)
                                continue
                            }
                            (flags and Protocol.FLAG_STATS) != 0 -> {
                                if (size >= 16) {
                                    val bb = ByteBuffer.wrap(buffer, 0, 16)
                                    remoteBitRate = bb.int; remoteRtt = bb.int; remoteFps = bb.int; remoteAudio = bb.int != 0
                                }
                                continue
                            }
                            (flags and Protocol.FLAG_AUDIO) != 0 -> {
                                if (audio == null) audio = createAudioTrack()
                                try { audio?.write(buffer, 0, size, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Exception) {}
                                continue
                            }
                        }
                        if (resized) break

                        val isConfig = (flags and Protocol.FLAG_CONFIG) != 0
                        if (isConfig) gotConfig = true
                        if (!gotConfig || size == 0) continue // без SPS/PPS декодировать нельзя

                        // Ждём свободный входной буфер, параллельно выводя готовые кадры.
                        var inIndex: Int
                        while (true) {
                            drainOutput(codec, info)
                            inIndex = codec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0 || !running) break
                        }
                        if (inIndex < 0) break

                        val inBuf = codec.getInputBuffer(inIndex) ?: continue
                        inBuf.clear()
                        inBuf.put(buffer, 0, size)
                        codec.queueInputBuffer(
                            inIndex, 0, size, ptsUs,
                            if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                        )
                        drainOutput(codec, info)
                    }
                } finally {
                    try { codec.stop() } catch (_: Exception) {}
                    try { codec.release() } catch (_: Exception) {}
                }
                if (!resized) break
            }
        } finally {
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
        }
    }

    private fun createAudioTrack(): AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(
            Protocol.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Protocol.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            // Небольшой буфер — меньше задержка; лишнее при заторе просто отбрасывается.
            .setBufferSizeInBytes(maxOf(min * 2, 19200))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    } catch (_: Exception) {
        null
    }

    private fun checkSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) throw IOException("Неверный размер")
    }

    /** Выводит на экран все готовые кадры сразу (минимальная задержка). */
    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val render = info.size > 0
                    codec.releaseOutputBuffer(index, render)
                    if (render) framesShown++
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // INFO_OUTPUT_FORMAT_CHANGED и т.п. — продолжаем
            }
        }
    }

    /** Отвечает телефону на запрос поиска. */
    private fun discoveryLoop() {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                discoverySocket = socket
                val reply = (Protocol.DISCOVER_REPLY_PREFIX + Protocol.deviceName(this)).toByteArray()
                val buf = ByteArray(256)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    if (text == Protocol.DISCOVER_REQUEST) {
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            }
        } catch (_: Exception) {
            // Сокет закрыт при остановке — это нормально.
        }
    }

    private fun waitForSurface(): Surface? {
        synchronized(surfaceLock) {
            while (running && surface == null) surfaceLock.wait(500)
            return surface
        }
    }

    // ---------- SurfaceHolder.Callback ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) {
            surface = holder.surface
            surfaceLock.notifyAll()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surface = null }
        // Поверхность пропала — разрываем текущее соединение, телефон переподключится сам.
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    // ---------- PIN, доверенные телефоны, настройки ----------

    private fun pinOn() = prefs.getBoolean(KEY_PIN_ON, true)
    private fun fillOn() = prefs.getBoolean(KEY_FILL, false)
    private fun trusted(): Set<String> = prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet()
    private fun trust(id: String) {
        prefs.edit().putStringSet(KEY_TRUSTED, trusted() + id).apply()
    }

    private fun toggleAutostart() {
        val on = !BootReceiver.isEnabled(this)
        BootReceiver.setEnabled(this, on)
        if (on && Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            // Без этого разрешения Android 10+ не даёт открыть приложение при включении приставки.
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                showHint("Разрешите «Поверх других приложений» и вернитесь назад", 6000)
            } catch (_: Exception) {
                showHint("Если автозапуск не сработает: Настройки → Приложения → Специальный доступ → " +
                    "«Поверх других приложений» → Экран на ТВ", 9000)
            }
        }
        renderWaiting()
    }

    // ---------- экран ожидания и показ ----------

    private fun isWaitingShown() = ::waiting.isInitialized && waiting.visibility == View.VISIBLE

    private fun showWaiting() {
        surfaceView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
        statsText.visibility = View.GONE
        waiting.visibility = View.VISIBLE
        renderWaiting()
        (if (updateButton.visibility == View.VISIBLE) updateButton else autostartButton).requestFocus()
    }

    private fun renderWaiting() {
        val ips = localIpAddresses()
        val ip = ips.firstOrNull()
        val sb = StringBuilder()
        sb.append("Ожидание подключения телефона…\n\n")
        sb.append("1. Отсканируйте QR-код камерой телефона\n")
        sb.append("2. Или в «Экране на ТВ» на телефоне нажмите «Найти приставку»\n\n")
        sb.append("IP: ").append(if (ips.isEmpty()) "нет сети" else ips.joinToString(", "))
        sb.append("   ·   Версия ").append(Updater.currentVersion(this))
        waitingNote?.let { sb.append("\n\n").append(it) }
        val r = Updater.release
        if (r != null) {
            when (Updater.stage) {
                Updater.Stage.DOWNLOADING -> sb.append("\n\n⬆ Скачиваю обновление ${r.version}… ${Updater.progress}%")
                Updater.Stage.INSTALLING -> sb.append("\n\n⬆ Устанавливаю ${r.version} — подтвердите установку на экране")
                Updater.Stage.PERMISSION -> sb.append("\n\n⬆ ").append(Updater.message.ifEmpty {
                    "Разрешите установку из этого источника и вернитесь — обновление продолжится само"
                })
                Updater.Stage.ERROR -> sb.append("\n\n⬆ Не удалось обновить (${Updater.message})")
                else -> Unit
            }
        }
        infoText.text = sb.toString()
        pinText.text = if (pinOn()) "PIN: $sessionPin" else ""
        pinText.visibility = if (pinOn()) View.VISIBLE else View.GONE

        if (ip != null) {
            val url = QR_PAGE + "?h=" + ip + (if (pinOn()) "&p=$sessionPin" else "") +
                "&n=" + URLEncoder.encode(Protocol.deviceName(this), "UTF-8").replace("+", "%20")
            qrView.setImageBitmap(qrBitmap(url))
            qrView.visibility = View.VISIBLE
        } else {
            qrView.visibility = View.GONE
        }

        updateButton.visibility = if (r != null) View.VISIBLE else View.GONE
        updateButton.text = if (r != null) "Обновить до ${r.version}" else ""
        updateButton.isEnabled = Updater.stage != Updater.Stage.DOWNLOADING && Updater.stage != Updater.Stage.INSTALLING
        autostartButton.text = "Автозапуск: " + if (BootReceiver.isEnabled(this)) "вкл" else "выкл"
        pinButton.text = "PIN: " + if (pinOn()) "вкл" else "выкл"
        fillButton.text = "Картинка: " + if (fillOn()) "заполнить экран" else "вписать"
    }

    private fun qrBitmap(text: String): Bitmap? = try {
        val qr = QrCode.encode(text)
        val border = 2
        val scale = 8
        val n = (qr.size + border * 2) * scale
        val pixels = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val dark = qr.get(x / scale - border, y / scale - border)
            pixels[y * n + x] = if (dark) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }

    /** Подгоняет SurfaceView: «вписать» (поля по краям) или «заполнить экран» (края обрезаются). */
    private fun showVideo(vw: Int, vh: Int) {
        waiting.visibility = View.GONE
        val cw = root.width
        val ch = root.height
        if (cw == 0 || ch == 0) return
        val sx = cw.toFloat() / vw
        val sy = ch.toFloat() / vh
        val scale = if (fillOn()) maxOf(sx, sy) else minOf(sx, sy)
        surfaceView.layoutParams = FrameLayout.LayoutParams((vw * scale).toInt(), (vh * scale).toInt(), Gravity.CENTER)
        renderStats()
    }

    private var lastFrames = 0
    private var lastBytes = 0L

    private fun renderStats() {
        val shown = !isWaitingShown() && activeSocket != null && prefs.getBoolean(KEY_STATS, false)
        val frames = framesShown
        val bytes = bytesIn
        val fps = (frames - lastFrames).coerceAtLeast(0)
        val mbit = ((bytes - lastBytes).coerceAtLeast(0) * 8) / 1_000_000.0
        lastFrames = frames
        lastBytes = bytes
        if (!shown) {
            statsText.visibility = View.GONE
            return
        }
        val sb = StringBuilder()
        sb.append(videoW).append('×').append(videoH).append(" · ").append(fps).append(" к/с")
        sb.append('\n').append(String.format(Locale.US, "%.1f", mbit).replace('.', ',')).append(" Мбит/с")
        if (remoteBitRate > 0) sb.append(" (цель ").append(String.format(Locale.US, "%.1f", remoteBitRate / 1_000_000.0).replace('.', ',')).append(')')
        sb.append('\n').append("Сеть: ").append(if (remoteRtt >= 0) "$remoteRtt мс" else "—")
        sb.append('\n').append("Звук: ").append(if (remoteAudio) "на ТВ" else "на телефоне")
        sb.append('\n').append("С «").append(activeName).append('»')
        statsText.text = sb.toString()
        statsText.visibility = View.VISIBLE
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    /** IP-адреса устройства: сначала проводная сеть и Wi‑Fi, потом остальные. */
    private fun localIpAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { val n = it.name.lowercase(); if (n.startsWith("eth") || n.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }
}
