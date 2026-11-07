package com.example.phonetotv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Поиск приставки в локальной сети. Блокирующий — вызывать не из главного потока.
 *
 * Многие роутеры не пропускают широковещательные пакеты между устройствами, поэтому запрос уходит тремя способами:
 *  1) на 255.255.255.255;
 *  2) на широковещательный адрес своей подсети (например, 192.168.1.255);
 *  3) напрямую на каждый адрес своей подсети (до 1024 адресов) — это обходит блокировку широковещания.
 * Приставка отвечает на любой из них.
 */
object Discovery {

    data class Found(val ip: String, val name: String)

    private const val MAX_HOSTS = 1024

    fun find(attempts: Int = 2, timeoutMs: Int = 1500): Found? {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val request = Protocol.DISCOVER_REQUEST.toByteArray()
            val targets = targets()
            repeat(attempts) {
                if (Thread.currentThread().isInterrupted) return null
                for (addr in targets) {
                    try {
                        socket.send(DatagramPacket(request, request.size, addr, Protocol.DISCOVERY_PORT))
                    } catch (_: Exception) {
                        // отдельные адреса могут быть недоступны — пропускаем
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val left = (deadline - System.currentTimeMillis()).toInt()
                    if (left <= 0) break
                    socket.soTimeout = left
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
                        break
                    }
                }
            }
        }
        return null
    }

    /** Адреса для запроса: общий широковещательный, широковещательные подсетей и все адреса подсетей. */
    private fun targets(): List<InetAddress> {
        val list = LinkedHashSet<InetAddress>()
        list.add(InetAddress.getByName("255.255.255.255"))
        val hosts = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                // VPN и мобильная сеть: там приставки нет
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("ccmni")) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix !in 16..30) continue
                    ia.broadcast?.let { list.add(it) }
                    val ip = toInt(addr)
                    val mask = -1 shl (32 - prefix)
                    val net = ip and mask
                    val count = (1L shl (32 - prefix)) - 2
                    if (count > MAX_HOSTS) {
                        // Большая сеть — опрашиваем только свои /24 соседей.
                        val net24 = ip and (-1 shl 8)
                        for (h in 1..254) if (net24 + h != ip) hosts.add(fromInt(net24 + h))
                    } else {
                        for (h in 1..count.toInt()) if (net + h != ip) hosts.add(fromInt(net + h))
                    }
                }
            }
        } catch (_: Exception) {
        }
        list.addAll(hosts)
        return list.toList()
    }

    private fun toInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun fromInt(v: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    )
}
