package app.switchflow.companion.direct

import java.util.Base64
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Contents of the QR code a receiving phone shows (docs/DIRECT.md). */
data class DirectTarget(val hosts: List<String>, val port: Int, val key: ByteArray, val name: String, val platform: String)

object DirectQr {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val keyChars = Regex("^[A-Za-z0-9_-]{43}$")

    fun build(t: DirectTarget): String {
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(t.key)
        val n = URLEncoder.encode(t.name.take(40), "UTF-8").replace("+", "%20")
        return "switchflow://direct?v=1&h=${t.hosts.joinToString(",")}&p=${t.port}&k=$k&n=$n&o=${t.platform}"
    }

    fun isDirect(raw: String) = raw.trim().startsWith("switchflow://direct")

    /** Null for anything that is not a well-formed SwitchFlow Direct v1 code. */
    fun parse(raw: String): DirectTarget? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "switchflow" || uri.host != "direct") return null
        val q = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to (runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull() ?: return@mapNotNull null)
        }.toMap()
        if (q["v"] != "1") return null
        val hosts = q["h"].orEmpty().split(',').map { it.trim() }.filter(::isIpv4).distinct().take(8)
        if (hosts.isEmpty()) return null
        val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val k = q["k"]?.takeIf { keyChars.matches(it) } ?: return null
        val key = runCatching { Base64.getUrlDecoder().decode(k) }.getOrNull()?.takeIf { it.size == DirectCrypto.KEY_BYTES } ?: return null
        val name = q["n"]?.filter { !it.isISOControl() }?.take(40)?.ifBlank { null } ?: "Phone"
        val platform = if (q["o"] == "ios") "ios" else "android"
        return DirectTarget(hosts, port, key, name, platform)
    }

    private fun isIpv4(s: String): Boolean {
        val m = ipv4.matchEntire(s) ?: return false
        return m.groupValues.drop(1).all { (it.toIntOrNull() ?: 256) in 0..255 }
    }
}

/** A connected, authenticated channel plus which end of it this phone is. */
class DirectLink(val channel: DirectChannel, val isClient: Boolean, val viaCable: Boolean)

/** Wi-Fi side: the receiver listens, the sender connects. */
object WifiTransport {
    const val PORT = 47830
    private const val SOCKET_BUFFER = 4 * 1024 * 1024

    /**
     * This phone's IPv4 addresses, Wi-Fi first, then a hotspot this phone runs, then the rest.
     * Mobile data addresses are left out: the other phone cannot reach them.
     */
    fun localAddresses(): List<String> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        fun rank(name: String) = when {
            name.startsWith("wlan") -> 0
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 1
            name.startsWith("eth") -> 2
            else -> 3
        }
        return all.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") && !it.name.startsWith("tun") && !it.name.startsWith("dummy") }
            .sortedBy { rank(it.name) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress } }
            .map { it.hostAddress!! }
            .distinct()
            .take(8)
    }

    /** Listens until [stop]; every accepted socket that passes the handshake goes to [onLink]. */
    class Listener(private val key: ByteArray, private val onLink: (DirectLink) -> Unit) {
        private val server: ServerSocket = try {
            ServerSocket().apply { reuseAddress = true; receiveBufferSize = SOCKET_BUFFER; bind(InetSocketAddress(PORT), 4) }
        } catch (e: IOException) {
            ServerSocket().apply { reuseAddress = true; receiveBufferSize = SOCKET_BUFFER; bind(InetSocketAddress(0), 4) }
        }
        val port get() = server.localPort
        @Volatile private var stopped = false

        init {
            thread(name = "sf-direct-accept", isDaemon = true) {
                while (!stopped) {
                    val s = try { server.accept() } catch (e: IOException) { break }
                    thread(name = "sf-direct-hs", isDaemon = true) { handshake(s) }
                }
            }
        }

        private fun handshake(s: Socket) {
            try {
                s.tcpNoDelay = true
                s.sendBufferSize = SOCKET_BUFFER
                s.soTimeout = 10_000 // a stranger that connects and says nothing
                val sessionKey = DirectHandshake.server(s.getInputStream(), s.getOutputStream(), key)
                s.soTimeout = 0 // silence is detected by the channel's keep-alive from here on
                onLink(DirectLink(DirectChannel(s.getInputStream(), s.getOutputStream(), sessionKey, false) { s.close() }, isClient = false, viaCable = false))
            } catch (e: IOException) {
                runCatching { s.close() }
            }
        }

        fun stop() {
            stopped = true
            runCatching { server.close() }
        }
    }

    /** Tries every address of the QR code at once and keeps the first that completes the handshake. */
    fun connect(t: DirectTarget, timeoutMs: Int = 4000): DirectLink? {
        val result = java.util.concurrent.atomic.AtomicReference<DirectLink?>(null)
        val threads = t.hosts.map { host ->
            thread(isDaemon = true) {
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    // Large socket buffers keep Wi-Fi busy while either phone pauses to save a file.
                    s.sendBufferSize = SOCKET_BUFFER
                    s.receiveBufferSize = SOCKET_BUFFER
                    s.connect(InetSocketAddress(InetAddress.getByName(host), t.port), timeoutMs)
                    s.soTimeout = 10_000
                    val sessionKey = DirectHandshake.client(s.getInputStream(), s.getOutputStream(), t.key, DirectHandshake.MODE_QR)
                    s.soTimeout = 0
                    val link = DirectLink(DirectChannel(s.getInputStream(), s.getOutputStream(), sessionKey, true) { s.close() }, isClient = true, viaCable = false)
                    if (!result.compareAndSet(null, link)) link.channel.close()
                } catch (e: IOException) {
                    runCatching { s.close() }
                }
            }
        }
        threads.forEach { it.join(timeoutMs + 12_000L) }
        return result.get()
    }
}
