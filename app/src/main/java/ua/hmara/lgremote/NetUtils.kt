package ua.hmara.lgremote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object NetUtils {

    /**
     * Wake-on-LAN: на webOS 3 ТВ "вимкнений" тримає мережу, якщо в
     * Налаштування → Загальні → "Mobile TV On" / "Увімкнення через Wi-Fi" = Увімк.
     * Надійніше працює по кабелю (LAN).
     */
    fun sendWol(mac: String, broadcast: String = "255.255.255.255") {
        val macBytes = mac.split(':', '-')
            .map { it.toInt(16).toByte() }
            .toByteArray()
        require(macBytes.size == 6) { "Невірна MAC-адреса" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 6 until packet.size step 6) System.arraycopy(macBytes, 0, packet, i, 6)

        DatagramSocket().use { s ->
            s.broadcast = true
            val addr = InetAddress.getByName(broadcast)
            repeat(3) {
                s.send(DatagramPacket(packet, packet.size, addr, 9))
                s.send(DatagramPacket(packet, packet.size, addr, 7))
            }
        }
    }

    /** Пошук LG webOS ТВ у мережі через SSDP. Повертає список IP. */
    fun discover(context: Context, timeoutMs: Int = 3000): List<String> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("lgremote-ssdp").apply { setReferenceCounted(true); acquire() }
        val found = linkedSetOf<String>()
        try {
            val query = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n").toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(query, query.size, group, 1900)) }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val resp = String(p.data, 0, p.length)
                        if (resp.contains("webos-second-screen", ignoreCase = true) ||
                            resp.contains("LG", ignoreCase = false)) {
                            p.address.hostAddress?.let { found += it }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } finally {
            lock.release()
        }
        return found.toList()
    }
}
