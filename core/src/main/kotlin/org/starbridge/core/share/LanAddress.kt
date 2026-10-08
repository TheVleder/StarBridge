package org.starbridge.core.share

/**
 * Which local address goes in the QR. A phone can have several private IPv4 addresses at once
 * (WiFi, a hotspot it hosts, WiFi Direct, a VPN, USB tethering…) and only some of them can be
 * reached from the iPhone on the same WiFi.
 */
object LanAddress {
    /** [candidates]: (interface name, IPv4 address), private addresses only. Best first, or null. */
    fun pick(candidates: List<Pair<String, String>>): String? =
        candidates.map { (name, ip) -> rank(name) to ip }
            .filter { it.first < EXCLUDED }
            .minByOrNull { it.first }?.second

    /** False for interfaces the other phones on the WiFi cannot reach (WiFi Direct, VPN…). */
    fun reachable(interfaceName: String) = rank(interfaceName) < EXCLUDED

    fun rank(interfaceName: String): Int {
        val n = interfaceName.lowercase()
        return when {
            n.startsWith("wlan") -> 0 // joined WiFi network (the iPhone hotspot)
            n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") -> 1 // hotspot hosted here
            n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("bt-pan") -> 2
            // WiFi Direct, VPN, mobile data, virtual: other phones on the WiFi cannot reach them.
            n.startsWith("p2p") || n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("rmnet") ||
                n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("ipsec") -> EXCLUDED
            else -> 5
        }
    }

    private const val EXCLUDED = 9
}
