package com.example.phonetotv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Поиск приставки в локальной сети UDP-широковещанием. Блокирующий — вызывать не из главного потока. */
object Discovery {

    data class Found(val ip: String, val name: String)

    fun find(attempts: Int = 3, timeoutMs: Int = 1500): Found? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val request = Protocol.DISCOVER_REQUEST.toByteArray()
            val broadcast = InetAddress.getByName("255.255.255.255")
            repeat(attempts) {
                if (Thread.currentThread().isInterrupted) return null
                socket.send(DatagramPacket(request, request.size, broadcast, Protocol.DISCOVERY_PORT))
                try {
                    val buf = ByteArray(256)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress
                    if (text.startsWith(Protocol.DISCOVER_REPLY_PREFIX) && ip != null) {
                        return Found(ip, text.removePrefix(Protocol.DISCOVER_REPLY_PREFIX))
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return null
    }
}
