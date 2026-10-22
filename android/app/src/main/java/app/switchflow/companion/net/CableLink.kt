package app.switchflow.companion.net

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Transfer over the USB cable without USB debugging (Android Open Accessory).
 *
 * The SwitchFlow PC switched the phone into accessory mode. Connections the app makes to
 * 127.0.0.1:[LOCAL_PORT] are carried over the accessory as streams and reach the PC's server,
 * so the HTTPS client, certificate pinning and the whole protocol work exactly as over Wi-Fi.
 * Frame: kind (1 byte) · stream (4, big-endian) · length (4) · payload — see desktop cable.rs.
 */
class CableLink private constructor(
    private val fd: ParcelFileDescriptor,
    private val onHello: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    companion object {
        const val LOCAL_PORT = 47821
        const val MANUFACTURER = "SwitchFlow"
        const val MODEL = "SwitchFlow Cable"

        private const val HEADER = 9
        /** Android reads the accessory in 16 KiB chunks; a frame never exceeds one read. */
        private const val MAX_TRANSFER = 16_384
        private const val HELLO: Byte = 1
        private const val OPEN: Byte = 2
        private const val DATA: Byte = 3
        private const val CLOSE: Byte = 4
        private const val READY: Byte = 5

        fun isOurs(a: UsbAccessory) = a.manufacturer == MANUFACTURER && a.model == MODEL

        /** Null when the accessory cannot be opened (no permission yet, or already open). */
        fun open(context: Context, accessory: UsbAccessory, onHello: (String) -> Unit, onClosed: () -> Unit): CableLink? {
            val usb = context.getSystemService(UsbManager::class.java) ?: return null
            if (!isOurs(accessory) || !usb.hasPermission(accessory)) return null
            val fd = runCatching { usb.openAccessory(accessory) }.getOrNull() ?: return null
            return CableLink(fd, onHello, onClosed).also { it.start() }
        }

        /** A write that is a multiple of the USB packet size leaves the PC waiting; 64 divides them all. */
        internal fun chunkLength(remaining: Int): Int {
            var n = minOf(remaining, MAX_TRANSFER - HEADER)
            if ((HEADER + n) % 64 == 0) n -= 1
            return n
        }
    }

    private val input = FileInputStream(fd.fileDescriptor)
    private val output = FileOutputStream(fd.fileDescriptor)
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val nextStream = AtomicInteger(1)
    private val open = AtomicBoolean(true)
    private var server: ServerSocket? = null

    val isOpen get() = open.get()

    private fun start() {
        server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_PORT), 16)
        }
        thread(name = "sf-cable-read", isDaemon = true) { readLoop() }
        thread(name = "sf-cable-accept", isDaemon = true) { acceptLoop() }
        send(READY, 0, """{"v":1}""".toByteArray())
    }

    fun close() {
        if (!open.compareAndSet(true, false)) return
        runCatching { server?.close() }
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { fd.close() }
        onClosed()
    }

    private fun acceptLoop() {
        val srv = server ?: return
        while (open.get()) {
            val s = try { srv.accept() } catch (e: IOException) { break }
            s.tcpNoDelay = true
            val id = nextStream.getAndIncrement()
            sockets[id] = s
            if (!send(OPEN, id, ByteArray(0))) { runCatching { s.close() }; break }
            thread(name = "sf-cable-stream", isDaemon = true) { pump(id, s) }
        }
    }

    /** Local connection → cable. */
    private fun pump(id: Int, s: Socket) {
        val buf = ByteArray(64 * 1024)
        try {
            val inp = s.getInputStream()
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                var off = 0
                while (off < n) {
                    val len = chunkLength(n - off)
                    if (!send(DATA, id, buf, off, len)) return
                    off += len
                }
            }
        } catch (_: IOException) {
        }
        if (sockets.remove(id) != null) {
            runCatching { s.close() }
            send(CLOSE, id, ByteArray(0))
        }
    }

    /** Cable → local connections. */
    private fun readLoop() {
        val buf = ByteArray(MAX_TRANSFER)
        val pending = ByteArrayOutputStream()
        try {
            while (open.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                pending.write(buf, 0, n)
                val data = pending.toByteArray()
                var pos = 0
                while (data.size - pos >= HEADER) {
                    val head = ByteBuffer.wrap(data, pos, HEADER)
                    val kind = head.get()
                    val stream = head.int
                    val len = head.int
                    if (len < 0 || len > MAX_TRANSFER) throw IOException("corrupt frame")
                    if (data.size - pos < HEADER + len) break
                    handle(kind, stream, data, pos + HEADER, len)
                    pos += HEADER + len
                }
                pending.reset()
                if (pos < data.size) pending.write(data, pos, data.size - pos)
            }
        } catch (_: IOException) {
            // Unplugged, or the PC closed the accessory.
        }
        close()
    }

    private fun handle(kind: Byte, stream: Int, data: ByteArray, off: Int, len: Int) {
        when (kind) {
            HELLO -> {
                val uri = runCatching { org.json.JSONObject(String(data, off, len, Charsets.UTF_8)).getString("uri") }.getOrNull()
                if (uri != null) onHello(uri)
            }
            DATA -> {
                val s = sockets[stream] ?: return
                try {
                    s.getOutputStream().write(data, off, len)
                } catch (_: IOException) {
                    if (sockets.remove(stream) != null) {
                        runCatching { s.close() }
                        send(CLOSE, stream, ByteArray(0))
                    }
                }
            }
            CLOSE -> sockets.remove(stream)?.let { runCatching { it.close() } }
        }
    }

    /** One frame = one write = one USB transfer. */
    private fun send(kind: Byte, stream: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): Boolean {
        var body = len
        // Control frames: pad JSON with a space instead of shortening (see chunkLength).
        val padded = kind != DATA && (HEADER + body) % 64 == 0
        if (padded) body += 1
        val frame = ByteBuffer.allocate(HEADER + body).put(kind).putInt(stream).putInt(body).put(payload, off, len)
        if (padded) frame.put(' '.code.toByte())
        return try {
            synchronized(output) { output.write(frame.array()) }
            true
        } catch (_: IOException) {
            close()
            false
        }
    }
}
