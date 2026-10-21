package app.switchflow.companion.data

import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** Reads the whole stream and returns (sha256, size). Closes the stream. */
    fun sha256(input: InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return hex(md.digest()) to total
    }
}
