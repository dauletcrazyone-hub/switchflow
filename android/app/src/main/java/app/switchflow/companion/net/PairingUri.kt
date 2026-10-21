package app.switchflow.companion.net

import java.net.URI
import java.net.URLDecoder

/** Contents of the QR code shown by the SwitchFlow PC. */
data class PairingTarget(
    val hosts: List<String>,
    val port: Int,
    /** SHA-256 of the PC's DER certificate, 64 lowercase hex chars. */
    val fingerprint: String,
    val secret: String,
    val desktopName: String,
)

object PairingUri {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val secretChars = Regex("^[A-Za-z0-9_-]{16,64}$")

    /** Returns null for anything that is not a well-formed SwitchFlow v1 pairing code. */
    fun parse(raw: String): PairingTarget? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "switchflow" || uri.host != "pair") return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull()
        }.filter { it.second != null }.associate { it.first to it.second!! }

        if (params["v"] != "1") return null
        val hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { isIpv4(it) }.distinct().take(8)
        if (hosts.isEmpty()) return null
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val fp = params["fp"]?.lowercase()?.takeIf { hex64.matches(it) } ?: return null
        val secret = params["s"]?.takeIf { secretChars.matches(it) } ?: return null
        val name = params["n"]?.filter { !it.isISOControl() }?.take(40)?.ifBlank { null } ?: "PC"
        return PairingTarget(hosts, port, fp, secret, name)
    }

    private fun isIpv4(s: String): Boolean {
        val m = ipv4.matchEntire(s) ?: return false
        return m.groupValues.drop(1).all { (it.toIntOrNull() ?: 256) in 0..255 }
    }
}
