package app.switchflow.companion.transfer

import app.switchflow.companion.data.Hashing
import app.switchflow.companion.net.ApiClient
import app.switchflow.companion.net.ApiException
import app.switchflow.companion.net.UploadEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Pause flag shared by the uploader and the receiver; set by the PC's pause/resume commands. */
class SessionControls {
    val paused = MutableStateFlow(false)
    suspend fun awaitRunning() {
        paused.first { !it }
    }
}

/** Thrown when the PC's view of an item differs from ours; the item restarts from the PC's offset. */
private class RestartItem : Exception()

/** Reading the original on this phone failed (deleted, moved, storage error) — not a network problem. */
private class SourceReadError : Exception()

/**
 * Source side: streams originals to the PC in 4 MiB chunks. Resumes from the offset
 * the PC reports, hashes everything it sends, and lets the PC verify the result.
 */
class Uploader(
    private val api: ApiClient,
    private val catalog: SourceCatalog,
    private val controls: SessionControls,
    private val onProgress: (bytes: Long, name: String?) -> Unit,
    private val onItemDone: () -> Unit,
) {
    companion object {
        const val CHUNK = 4 * 1024 * 1024
        private const val MAX_ATTEMPTS = 4
    }

    suspend fun upload(sid: String, entry: UploadEntry, displayName: String?) {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                if (uploadOnce(sid, entry, displayName)) {
                    onItemDone()
                    return
                }
            } catch (e: RestartItem) {
                continue
            } catch (e: SourceReadError) {
                failQuietly(sid, entry.key, "source_read_failed")
                onItemDone()
                return
            } catch (e: ApiException) {
                when {
                    e.status == 404 || e.status == 410 -> return // session gone or cancelled
                    e.code == "checksum_mismatch" -> continue
                    // Any other refusal is about this file only: report it and move on to the next one.
                    else -> {
                        failQuietly(sid, entry.key, "source_read_failed")
                        onItemDone()
                        return
                    }
                }
            }
        }
        failQuietly(sid, entry.key, "checksum_mismatch")
        onItemDone()
    }

    private suspend fun failQuietly(sid: String, key: String, code: String) {
        runCatching { api.fail(sid, key, code) }
    }

    /** Returns true when the item is finished (staged, skipped or reported as failed). */
    private suspend fun uploadOnce(sid: String, entry: UploadEntry, displayName: String?): Boolean {
        val status = api.uploadStatus(sid, entry.key)
        if (status.state != "pending" && status.state != "receiving") return true
        var offset = status.receivedBytes

        val input: InputStream = try {
            catalog.open(entry.sourceId)
        } catch (e: FileNotFoundException) {
            api.fail(sid, entry.key, "not_found")
            return true
        } catch (e: SecurityException) {
            api.fail(sid, entry.key, "permission_denied")
            return true
        }

        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        input.use { s ->
            // The PC already has the first `offset` bytes: hash them without sending.
            var skipped = 0L
            while (skipped < offset) {
                val n = readSource { s.read(buf, 0, minOf(buf.size.toLong(), offset - skipped).toInt()) }
                if (n < 0) break
                md.update(buf, 0, n)
                skipped += n
            }
            if (skipped < offset) {
                // The original got shorter since the upload started: report instead of corrupting.
                api.fail(sid, entry.key, "source_read_failed")
                return true
            }
            while (true) {
                controls.awaitRunning()
                val n = readSource { readFully(s, buf) }
                if (n <= 0) break
                md.update(buf, 0, n)
                sendChunk(sid, entry.key, offset, buf, n)
                offset += n
                onProgress(n.toLong(), displayName)
            }
        }
        api.complete(sid, entry.key, offset, Hashing.hex(md.digest()))
        return true
    }

    private suspend fun sendChunk(sid: String, key: String, offset: Long, buf: ByteArray, len: Int) {
        var networkErrors = 0
        while (true) {
            try {
                api.putChunk(sid, key, offset, buf, len)
                return
            } catch (e: ApiException) {
                when (e.status) {
                    423 -> { controls.paused.value = true; controls.awaitRunningOrPoll() }
                    429 -> delay(2000) // the PC buffer is full: the target is catching up
                    409 -> throw RestartItem()
                    else -> throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Wi-Fi blips: retry the same chunk; the PC discards nothing it has verified.
                if (++networkErrors > 20) throw e
                delay(minOf(10_000L, 1000L * networkErrors))
            }
        }
    }

    private inline fun <T> readSource(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw SourceReadError()
        } catch (e: SecurityException) {
            throw SourceReadError()
        }

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = s.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

/** While paused by a 423, retry every few seconds even if the resume command was missed. */
suspend fun SessionControls.awaitRunningOrPoll() {
    var waited = 0
    while (paused.value && waited < 3) {
        delay(1000)
        waited++
    }
    paused.value = false
}
