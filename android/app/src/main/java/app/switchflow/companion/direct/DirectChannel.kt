package app.switchflow.companion.direct

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Phone-to-phone transport of docs/DIRECT.md: handshake, AES-GCM records, keep-alive. */
object DirectCrypto {
    const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            val n = minOf(prev.size, length - pos)
            System.arraycopy(prev, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun sessionKey(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        hkdf(key, clientNonce + serverNonce, "SwitchFlow direct v1".toByteArray(), KEY_BYTES)

    fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(direction).put(0).put(0).put(0).putLong(counter).array()
}

/** Wrong key, tampering or a peer that is not SwitchFlow. */
class DirectAuthException : IOException("direct: authentication failed")

/** Both sides' clear-text opening of docs/DIRECT.md. Returns the session key. */
object DirectHandshake {
    private val MAGIC = "SFD1".toByteArray()
    const val MODE_QR: Byte = 1
    const val MODE_CABLE: Byte = 2

    fun client(input: InputStream, output: OutputStream, key: ByteArray, mode: Byte): ByteArray {
        val clientNonce = DirectCrypto.randomBytes(16)
        val hello = MAGIC + byteArrayOf(mode) + (if (mode == MODE_CABLE) key else ByteArray(0)) + clientNonce
        output.write(hello)
        output.flush()
        val reply = readExactly(input, 20)
        if (!reply.copyOfRange(0, 4).contentEquals(MAGIC)) throw DirectAuthException()
        return DirectCrypto.sessionKey(key, clientNonce, reply.copyOfRange(4, 20))
    }

    /** [key] is the QR key; null on the cable, where the client sends it. */
    fun server(input: InputStream, output: OutputStream, key: ByteArray?): ByteArray {
        val head = readExactly(input, 5)
        if (!head.copyOfRange(0, 4).contentEquals(MAGIC)) throw DirectAuthException()
        val sessionBase = when (head[4]) {
            MODE_QR -> key ?: throw DirectAuthException()
            MODE_CABLE -> if (key == null) readExactly(input, DirectCrypto.KEY_BYTES) else throw DirectAuthException()
            else -> throw DirectAuthException()
        }
        val clientNonce = readExactly(input, 16)
        val serverNonce = DirectCrypto.randomBytes(16)
        output.write(MAGIC + serverNonce)
        output.flush()
        return DirectCrypto.sessionKey(sessionBase, clientNonce, serverNonce)
    }

    internal fun readExactly(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        DataInputStream(input).readFully(b)
        return b
    }
}

/** One decrypted record; its bytes are `body[offset until offset + length]`. */
class DirectRecord(val type: Int, val body: ByteArray, val offset: Int, val length: Int) {
    fun text() = String(body, offset, length, Charsets.UTF_8)
}

object DirectType {
    const val HELLO = 1
    const val ROLE = 2
    const val OFFER = 3
    const val ACCEPT = 4
    const val ITEM = 5
    const val DATA = 6
    const val END = 7
    const val ACK = 8
    const val DONE = 9
    const val FINISH = 10
    const val CANCEL = 11
    const val PING = 12
}

/**
 * Encrypted, ordered records over any byte stream (TCP socket or USB bulk pipes).
 * Writes are serialised; one thread reads. [close] unblocks both.
 */
class DirectChannel(
    input: InputStream,
    private val output: OutputStream,
    sessionKey: ByteArray,
    isClient: Boolean,
    private val closer: () -> Unit,
) {
    companion object {
        const val MAX_RECORD = 1024 * 1024 + 64
        /** Data per record. Measured over loopback: 256 KiB moves ~3x more per second than 1 MiB records. */
        const val CHUNK = 256 * 1024
        private const val TAG_BITS = 128
        const val PING_EVERY_MS = 10_000L
        const val SILENCE_LIMIT_MS = 45_000L
    }

    // Buffered only after the handshake, which must not read past its own bytes.
    private val input = DataInputStream(java.io.BufferedInputStream(input, 64 * 1024))
    private val key = SecretKeySpec(sessionKey, "AES")
    private val sendDir: Byte = if (isClient) 'C'.code.toByte() else 'S'.code.toByte()
    private val recvDir: Byte = if (isClient) 'S'.code.toByte() else 'C'.code.toByte()
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var lastSent = System.currentTimeMillis()
    /** Silence counts only while the reader waits; a slow save on this phone is not a dead link. */
    @Volatile private var waitingSince = 0L
    private val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
    private val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
    private var readBuf = ByteArray(64 * 1024)
    // Reused under the write lock: no fresh megabyte arrays per record for the garbage collector.
    private var plainBuf = ByteArray(0)
    private var frameBuf = ByteArray(0)

    val isOpen get() = !closed.get()

    fun send(type: Int, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        sendRecord(type, b, 0, b.size)
    }

    fun sendData(buf: ByteArray, off: Int, len: Int) = sendRecord(DirectType.DATA, buf, off, len)

    private fun sendRecord(type: Int, body: ByteArray, off: Int, len: Int) {
        synchronized(writeLock) {
            if (closed.get()) throw IOException("direct: closed")
            val plainLen = len + 1
            if (plainBuf.size < plainLen) plainBuf = ByteArray(plainLen)
            plainBuf[0] = type.toByte()
            System.arraycopy(body, off, plainBuf, 1, len)
            if (frameBuf.size < plainLen + 4 + 16) frameBuf = ByteArray(plainLen + 4 + 16)
            encrypt.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, DirectCrypto.nonce(sendDir, sendCounter++)))
            // One call: some providers return part of the output from update(), which would be lost.
            val sealed = encrypt.doFinal(plainBuf, 0, plainLen, frameBuf, 4)
            ByteBuffer.wrap(frameBuf, 0, 4).putInt(sealed)
            try {
                output.write(frameBuf, 0, 4 + sealed)
                output.flush()
            } catch (e: IOException) {
                close()
                throw e
            }
            lastSent = System.currentTimeMillis()
        }
    }

    /** Blocks for the next record that is not a keep-alive. Throws when the link is gone. */
    fun receive(): DirectRecord {
        while (true) {
            val r = receiveAny()
            if (r.type != DirectType.PING) return r
        }
    }

    private fun receiveAny(): DirectRecord {
        waitingSince = System.currentTimeMillis()
        val len = try {
            input.readInt()
        } catch (e: EOFException) {
            close(); throw IOException("direct: closed by peer")
        } catch (e: IOException) {
            close(); throw e
        }
        if (len < 17 || len > MAX_RECORD) { close(); throw DirectAuthException() }
        if (readBuf.size < len) readBuf = ByteArray(maxOf(len, readBuf.size * 2).coerceAtMost(MAX_RECORD))
        try {
            input.readFully(readBuf, 0, len)
        } catch (e: IOException) {
            close(); throw e
        }
        val plain = try {
            decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, DirectCrypto.nonce(recvDir, recvCounter++)))
            decrypt.doFinal(readBuf, 0, len)
        } catch (e: AEADBadTagException) {
            close(); throw DirectAuthException()
        } catch (e: java.security.GeneralSecurityException) {
            close(); throw DirectAuthException()
        }
        waitingSince = 0L
        return DirectRecord(plain[0].toInt() and 0xff, plain, 1, plain.size - 1)
    }

    /** Sends PINGs while idle and closes the link after [SILENCE_LIMIT_MS] without any record. */
    fun startKeepAlive() {
        thread(name = "sf-direct-ping", isDaemon = true) {
            while (!closed.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val now = System.currentTimeMillis()
                val since = waitingSince
                if (since != 0L && now - since > SILENCE_LIMIT_MS) { close(); return@thread }
                if (now - lastSent >= PING_EVERY_MS) runCatching { send(DirectType.PING, "{}") }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { closer() }
    }
}

object Sha256 {
    fun hex(md: MessageDigest): String = md.digest().joinToString("") { "%02x".format(it) }
}
