package com.example.phonetotv

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Принимает H.264-поток с телефона по TCP и выводит на весь экран ТВ.
 *  - при повороте телефона (FLAG_RESIZE) пересоздаёт декодер под новый размер;
 *  - новое подключение телефона сразу заменяет старое (после обрыва Wi‑Fi телефон переподключается сам).
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private companion object {
        /** Нет данных дольше — соединение считаем мёртвым (телефон шлёт кадры минимум 10 раз в секунду). */
        const val READ_TIMEOUT_MS = 8000
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var infoText: TextView

    @Volatile private var surface: Surface? = null
    @Volatile private var running = false

    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var clientThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Сообщение под «Ожидание подключения» (например, о несовпадении версий). */
    @Volatile private var waitingNote: String? = null

    private val surfaceLock = Object()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))

        infoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(infoText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        hideSystemUi()
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
        thread(name = "video-server") { serverLoop() }
        thread(name = "discovery") { discoveryLoop() }
    }

    override fun onStop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        synchronized(surfaceLock) { surfaceLock.notifyAll() }
        multicastLock?.release()
        multicastLock = null
        super.onStop()
    }

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- Сеть ----------

    /** Принимает подключения. Новое подключение закрывает старое и ждёт, пока освободится декодер. */
    private fun serverLoop() {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(Protocol.VIDEO_PORT))
                serverSocket = server
                while (running) {
                    val client = try { server.accept() } catch (e: IOException) { break }
                    // Закрываем прежнее соединение (например, «зависшее» после обрыва Wi‑Fi).
                    try { clientSocket?.close() } catch (_: Exception) {}
                    clientThread?.join(3000)
                    clientSocket = client
                    clientThread = thread(name = "video-client") { runClient(client) }
                }
            }
        } catch (e: Exception) {
            if (running) runOnUiThread { infoText.text = "Ошибка сервера: ${e.message}" }
        }
    }

    private fun runClient(client: Socket) {
        try {
            handleClient(client)
        } catch (_: Exception) {
            // Телефон отключился или поток повреждён — ждём следующего подключения.
        } finally {
            try { client.close() } catch (_: Exception) {}
            if (clientSocket === client) {
                clientSocket = null
                if (running) runOnUiThread { showWaiting() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.receiveBufferSize = 1024 * 1024
        client.soTimeout = READ_TIMEOUT_MS
        val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))

        when (input.readInt()) {
            Protocol.MAGIC -> waitingNote = null
            Protocol.MAGIC_V1 -> {
                waitingNote = "Обновите приложение «Экран на ТВ» на телефоне — версии не совпадают"
                return
            }
            else -> throw IOException("Неизвестный протокол")
        }
        var width = input.readInt()
        var height = input.readInt()
        val from = client.inetAddress.hostAddress ?: ""
        var buffer = ByteArray(256 * 1024)

        // Каждый проход — один декодер под текущий размер. FLAG_RESIZE начинает новый проход.
        while (running && client === clientSocket) {
            checkSize(width, height)
            val outSurface = waitForSurface() ?: return
            val w = width
            val h = height
            runOnUiThread { showVideo(w, h, from) }

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

                    if ((flags and Protocol.FLAG_RESIZE) != 0) {
                        if (size < 8) throw IOException("Неверный пакет смены размера")
                        val bb = ByteBuffer.wrap(buffer, 0, 8)
                        width = bb.int
                        height = bb.int
                        resized = true
                        break
                    }

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
    }

    private fun checkSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) throw IOException("Неверный размер")
    }

    /** Выводит на экран все готовые кадры сразу (минимальная задержка). */
    private fun drainOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> codec.releaseOutputBuffer(index, info.size > 0)
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // INFO_OUTPUT_FORMAT_CHANGED и т.п. — продолжаем
            }
        }
    }

    /** Отвечает телефону на широковещательный запрос поиска. */
    private fun discoveryLoop() {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                discoverySocket = socket
                val reply = (Protocol.DISCOVER_REPLY_PREFIX + deviceName()).toByteArray()
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
        try { clientSocket?.close() } catch (_: Exception) {}
    }

    // ---------- UI ----------

    private fun showWaiting() {
        val ips = localIpAddresses()
        val ipText = if (ips.isEmpty()) "нет сети" else ips.joinToString("\n")
        val note = waitingNote?.let { "\n\n$it" } ?: ""
        val back = if (LauncherActivity.isTv(this)) "" else "\n\nНазад — сменить режим"
        infoText.text = "Ожидание подключения телефона…\n\nIP этого устройства:\n$ipText\n\n" +
            "Порт ${Protocol.VIDEO_PORT}$note$back"
        infoText.visibility = View.VISIBLE
    }

    /** Подгоняет SurfaceView под пропорции видео (чёрные поля по краям). */
    private fun showVideo(videoW: Int, videoH: Int, from: String) {
        infoText.visibility = View.GONE
        val containerW = root.width
        val containerH = root.height
        if (containerW == 0 || containerH == 0) return
        val scale = minOf(containerW.toFloat() / videoW, containerH.toFloat() / videoH)
        val lp = FrameLayout.LayoutParams((videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER)
        surfaceView.layoutParams = lp
        title = "Трансляция с $from"
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

    private fun deviceName(): String {
        val name = if (Build.VERSION.SDK_INT >= 25) {
            try {
                Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return name ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun localIpAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }
}
