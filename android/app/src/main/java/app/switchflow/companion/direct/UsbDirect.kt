package app.switchflow.companion.direct

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two Android phones joined by a Type-C cable (docs/DIRECT.md). The phone that became the
 * USB host switches the other into Android Open Accessory mode; then the bulk pipes are a
 * plain byte stream for [DirectChannel]. The host is the handshake's client and makes the key.
 */
object UsbDirect {
    const val MANUFACTURER = "SwitchFlow"
    const val MODEL = "SwitchFlow Direct"
    private const val GOOGLE_VID = 0x18d1
    /** Accessory (and accessory + ADB / audio) product ids of AOA 1.0 and 2.0. */
    private val ACCESSORY_PIDS = 0x2d00..0x2d05

    /** Largest single USB transfer; also what Android reads from an accessory in one go. */
    private const val TRANSFER = 16_384
    private const val WRITE_MAX = 16_000

    fun isOurAccessory(a: UsbAccessory) = a.manufacturer == MANUFACTURER && a.model == MODEL

    fun isInAccessoryMode(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in ACCESSORY_PIDS

    /**
     * Phones show up as MTP/PTP (class 6), or vendor-specific when set to "charging only".
     * Keyboards, flash drives, headsets and hubs are left alone.
     */
    fun looksLikePhone(d: UsbDevice): Boolean {
        if (isInAccessoryMode(d)) return true
        if (d.deviceClass == UsbConstants.USB_CLASS_HUB) return false
        val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }
        if (classes.any { it == UsbConstants.USB_CLASS_HID || it == UsbConstants.USB_CLASS_MASS_STORAGE || it == UsbConstants.USB_CLASS_AUDIO }) return false
        return classes.any { it == UsbConstants.USB_CLASS_STILL_IMAGE || it == UsbConstants.USB_CLASS_VENDOR_SPEC }
    }

    /** A write that is a multiple of the packet size leaves the reader waiting; 64 divides them all. */
    internal fun chunkLength(remaining: Int): Int {
        var n = minOf(remaining, WRITE_MAX)
        if (n % 64 == 0) n -= 1
        return n
    }

    /**
     * Asks the phone on the other end to restart as a SwitchFlow accessory (AOA "start").
     * It then disconnects and comes back as 18D1:2D0x. False when it is not an Android phone.
     */
    fun switchToAccessory(usb: UsbManager, device: UsbDevice): Boolean {
        val c = usb.openDevice(device) ?: return false
        try {
            val version = ByteArray(2)
            if (c.controlTransfer(0xC0, 51, 0, 0, version, 2, 1000) != 2) return false
            val protocol = (version[0].toInt() and 0xff) or ((version[1].toInt() and 0xff) shl 8)
            if (protocol < 1) return false
            val strings = listOf(MANUFACTURER, MODEL, "SwitchFlow phone-to-phone transfer", "1", "", "SF-DIRECT")
            strings.forEachIndexed { i, s ->
                val b = s.toByteArray(Charsets.UTF_8) + 0
                if (c.controlTransfer(0x40, 52, 0, i, b, b.size, 1000) < 0) return false
            }
            return c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) >= 0
        } finally {
            c.close()
        }
    }

    /** Host side, once the other phone is in accessory mode and we have permission for it. */
    fun openHost(usb: UsbManager, device: UsbDevice): DirectLink? {
        val conn = usb.openDevice(device) ?: return null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        var claimed: android.hardware.usb.UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            var a: UsbEndpoint? = null
            var b: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) a = ep else b = ep
            }
            if (a != null && b != null && conn.claimInterface(itf, true)) {
                inEp = a; outEp = b; claimed = itf
                break
            }
        }
        if (inEp == null || outEp == null) {
            conn.close()
            return null
        }
        val closed = AtomicBoolean(false)
        val close = {
            if (closed.compareAndSet(false, true)) {
                runCatching { conn.releaseInterface(claimed) }
                conn.close()
            }
        }
        val input = HostInput(conn, inEp, closed)
        val output = HostOutput(conn, outEp, closed)
        return try {
            val key = DirectCrypto.randomBytes(DirectCrypto.KEY_BYTES)
            val sessionKey = withTimeout(10_000, close) { DirectHandshake.client(input, output, key, DirectHandshake.MODE_CABLE) }
            DirectLink(DirectChannel(input, output, sessionKey, true, close), isClient = true, viaCable = true)
        } catch (e: IOException) {
            close()
            null
        }
    }

    /** Accessory side: the other phone switched us into SwitchFlow accessory mode. */
    fun openAccessory(context: Context, accessory: UsbAccessory): DirectLink? {
        val usb = context.getSystemService(UsbManager::class.java) ?: return null
        if (!isOurAccessory(accessory) || !usb.hasPermission(accessory)) return null
        val fd: ParcelFileDescriptor = runCatching { usb.openAccessory(accessory) }.getOrNull() ?: return null
        val close = { runCatching { fd.close() }; Unit }
        val input = AccessoryInput(FileInputStream(fd.fileDescriptor))
        val output = ChunkedOutput(FileOutputStream(fd.fileDescriptor))
        return try {
            val sessionKey = withTimeout(10_000, close) { DirectHandshake.server(input, output, null) }
            DirectLink(DirectChannel(input, output, sessionKey, false, close), isClient = false, viaCable = true)
        } catch (e: IOException) {
            close()
            null
        }
    }

    /** Runs [block]; if it is still stuck after [ms] (the peer never answered), closes the link to unblock it. */
    private fun <T> withTimeout(ms: Long, close: () -> Unit, block: () -> T): T {
        val done = AtomicBoolean(false)
        val watchdog = kotlin.concurrent.thread(isDaemon = true) {
            try {
                Thread.sleep(ms)
                if (!done.get()) close()
            } catch (_: InterruptedException) {
            }
        }
        try {
            return block()
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    private class HostInput(private val c: UsbDeviceConnection, private val ep: UsbEndpoint, private val closed: AtomicBoolean) : InputStream() {
        private val buf = ByteArray(TRANSFER)
        private var pos = 0
        private var end = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= end) {
                if (closed.get()) throw IOException("usb: closed")
                // bulkTransfer returns -1 both on timeout and on error; a detach closes the link.
                val n = c.bulkTransfer(ep, buf, TRANSFER, 1000)
                if (n > 0) { pos = 0; end = n }
            }
            val n = minOf(len, end - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    private class HostOutput(private val c: UsbDeviceConnection, private val ep: UsbEndpoint, private val closed: AtomicBoolean) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = off
            val stop = off + len
            var failures = 0
            while (p < stop) {
                if (closed.get()) throw IOException("usb: closed")
                val n = c.bulkTransfer(ep, b, p, chunkLength(stop - p), 5000)
                if (n > 0) {
                    p += n
                    failures = 0
                } else if (++failures > 6) {
                    throw IOException("usb: write failed")
                }
            }
        }
    }

    /** Android hands an accessory's data out per USB transfer; a smaller read buffer loses the rest. */
    private class AccessoryInput(private val inner: FileInputStream) : InputStream() {
        private val buf = ByteArray(TRANSFER)
        private var pos = 0
        private var end = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= end) {
                val n = inner.read(buf, 0, TRANSFER)
                if (n < 0) return -1
                pos = 0; end = n
            }
            val n = minOf(len, end - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }

    private class ChunkedOutput(private val inner: FileOutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var p = off
            val stop = off + len
            while (p < stop) {
                val n = chunkLength(stop - p)
                inner.write(b, p, n)
                p += n
            }
        }
    }
}
