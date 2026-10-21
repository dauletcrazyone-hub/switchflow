package app.switchflow.companion.transfer

import android.content.Context
import app.switchflow.companion.data.CalendarExport
import app.switchflow.companion.data.CalendarRepo
import app.switchflow.companion.data.ContactsRepo
import app.switchflow.companion.data.Hashing
import app.switchflow.companion.data.MediaStoreRepo
import app.switchflow.companion.data.VCardCodec
import app.switchflow.companion.net.AckBody
import app.switchflow.companion.net.ApiClient
import app.switchflow.companion.net.ApiException
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.DeliveryItem
import app.switchflow.companion.net.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Target side: pulls verified items from the PC, saves them through MediaStore /
 * Contacts Provider / CalendarContract, re-reads what was saved and acknowledges
 * with the SHA-256 of the stored bytes.
 */
class Receiver(
    private val context: Context,
    private val api: ApiClient,
    private val controls: SessionControls,
    private val onProgress: (bytes: Long, name: String?) -> Unit,
    private val onItemDone: (ok: Boolean) -> Unit,
) {
    private val media = MediaStoreRepo(context)

    /** Returns "done" or "cancelled" when the PC says the session is over. */
    suspend fun run(sid: String): String {
        var errors = 0
        while (coroutineContext.isActive) {
            val r = try {
                api.next(sid, 25)
            } catch (e: ApiException) {
                if (e.status == 404) return "cancelled"
                delay(2000)
                continue
            } catch (e: IOException) {
                delay(minOf(10_000L, 1000L * ++errors))
                continue
            }
            errors = 0
            when (r.state) {
                "item" -> {
                    controls.paused.value = false
                    handle(sid, r.item ?: continue)
                }
                "paused" -> { controls.paused.value = true; delay(1500) }
                "done", "cancelled" -> return r.state
                else -> controls.paused.value = false
            }
        }
        return "cancelled"
    }

    private suspend fun handle(sid: String, item: DeliveryItem) {
        val ack = try {
            when (item.category) {
                Category.CONTACTS -> saveContacts(sid, item)
                Category.CALENDARS -> saveCalendars(sid, item)
                else -> saveMedia(sid, item)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status == 410 || e.status == 404) throw CancellationException("session closed")
            AckBody("failed", error = "io_error")
        } catch (e: SecurityException) {
            AckBody("failed", error = "permission_denied")
        } catch (e: IOException) {
            val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true
            AckBody("failed", error = if (noSpace) "insufficient_storage" else "io_error")
        } catch (e: IllegalArgumentException) {
            AckBody("failed", error = "unsupported_format")
        }
        sendAck(sid, item.key, ack)
        onItemDone(ack.result != "failed")
    }

    private suspend fun sendAck(sid: String, key: String, ack: AckBody) {
        // The file is already saved; never lose that fact to a network blip.
        repeat(10) { attempt ->
            try {
                api.ack(sid, key, ack)
                return
            } catch (e: ApiException) {
                if (e.status in 400..499) return
            } catch (e: IOException) {
                delay(1000L * (attempt + 1))
            }
        }
    }

    /** Downloads [item] into [sink], resuming with HTTP Range after interruptions. Returns its SHA-256. */
    private suspend fun download(sid: String, item: DeliveryItem, sink: (ByteArray, Int) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var failures = 0
        val buf = ByteArray(256 * 1024)
        var complete = false
        while (!complete) {
            controls.awaitRunning()
            try {
                api.openContent(sid, item.key, written).use { input ->
                    while (true) {
                        val n = withContext(Dispatchers.IO) { input.read(buf) }
                        if (n < 0) break
                        sink(buf, n)
                        md.update(buf, 0, n)
                        written += n
                        onProgress(n.toLong(), item.name)
                    }
                }
                if (written < item.size) throw IOException("connection closed early")
                complete = true
            } catch (e: ApiException) {
                if (e.status == 423) { controls.paused.value = true; controls.awaitRunningOrPoll(); continue }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.contains("ENOSPC") == true) throw e
                if (++failures > 8) throw e
                delay(minOf(10_000L, 1000L * failures))
            }
        }
        return Hashing.hex(md.digest())
    }

    private suspend fun saveMedia(sid: String, item: DeliveryItem): AckBody {
        if (media.existsInTarget(item.category, item.name, item.size, item.albumName)) {
            return AckBody("duplicate")
        }
        val entry = withContext(Dispatchers.IO) { media.createPending(item.category, item.name, item.mime, item.createdMs, item.albumName) }
        var keep = false
        try {
            val sha = entry.output.use { out -> download(sid, item) { b, n -> out.write(b, 0, n) } }
            if (sha != item.sha256) return AckBody("failed", error = "checksum_mismatch")
            withContext(Dispatchers.IO) { media.publish(entry.uri, item.category, item.createdMs) }
            // Proof of storage: read the saved file back from MediaStore and hash it again.
            val (stored, size) = withContext(Dispatchers.IO) { media.readBackSha256(entry.uri) }
            if (stored != item.sha256 || size != item.size) return AckBody("failed", error = "checksum_mismatch")
            keep = true
            return AckBody("saved", sha256 = stored, verification = "read_back")
        } finally {
            if (!keep) withContext(Dispatchers.IO) { media.discard(entry.uri) }
        }
    }

    private suspend fun fetchSmall(sid: String, item: DeliveryItem): Pair<ByteArray, String> {
        if (item.size > 64L * 1024 * 1024) throw IllegalArgumentException("export too large")
        val bytes = ByteArrayOutputStream(item.size.toInt().coerceAtLeast(16))
        val sha = download(sid, item) { b, n -> bytes.write(b, 0, n) }
        return bytes.toByteArray() to sha
    }

    private suspend fun saveContacts(sid: String, item: DeliveryItem): AckBody {
        val (bytes, sha) = fetchSmall(sid, item)
        if (sha != item.sha256) return AckBody("failed", error = "checksum_mismatch")
        val cards = VCardCodec.parse(String(bytes, Charsets.UTF_8))
        val detail = withContext(Dispatchers.IO) { ContactsRepo(context).import(cards, item.contactsPolicy) }
        return AckBody("saved", sha256 = sha, verification = "written_file", detail = detail)
    }

    private suspend fun saveCalendars(sid: String, item: DeliveryItem): AckBody {
        val (bytes, sha) = fetchSmall(sid, item)
        if (sha != item.sha256) return AckBody("failed", error = "checksum_mismatch")
        val export = ProtocolJson.decodeFromString(CalendarExport.serializer(), String(bytes, Charsets.UTF_8))
        val detail = withContext(Dispatchers.IO) { CalendarRepo(context).import(export.events) }
        return AckBody("saved", sha256 = sha, verification = "written_file", detail = detail)
    }
}
