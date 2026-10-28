package app.switchflow.companion.direct

import android.content.Context
import android.os.Build
import app.switchflow.companion.core.DeviceEnv
import app.switchflow.companion.data.AppInbox
import app.switchflow.companion.data.AppsRepo
import app.switchflow.companion.data.CalendarExport
import app.switchflow.companion.data.CalendarRepo
import app.switchflow.companion.data.ContactsRepo
import app.switchflow.companion.data.Hashing
import app.switchflow.companion.data.MediaStoreRepo
import app.switchflow.companion.data.VCardCodec
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.ProtocolJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Extra category, phone to phone only: installer files of apps (Android → Android). */
object DirectCategory {
    const val APPS = "apps"
    val ORDER = listOf(Category.CONTACTS, Category.CALENDARS, Category.PHOTOS, Category.VIDEOS, Category.MUSIC, Category.DOCUMENTS, APPS)
}

/**
 * Saving on this phone: media through MediaStore with a read-back check, contacts and calendars
 * through their providers, apps into the install inbox.
 */
class AndroidSink(
    private val context: Context,
    /** Blocks until the user answered the system permission dialogs for these categories. */
    private val askWrite: (categories: List<String>) -> Unit,
) : DirectSink {
    private val media = MediaStoreRepo(context)
    private val apps = AppsRepo(context)
    val inbox = AppInbox(context)

    override fun plan(offer: Offer, done: Set<String>): Accept {
        val cats = offer.items.map { it.c }.distinct()
        askWrite(cats.filter { it != DirectCategory.APPS })
        val own = cats.filter { it in MEDIA }.associateWith { runCatching { media.ownFiles(it) }.getOrDefault(emptySet()) }
        val why = LinkedHashMap<String, String>()
        for (item in offer.items) {
            val reason = when {
                item.k in done -> "done"
                item.c in MEDIA && media.targetKey(item.c, item.n, item.s, item.a) in own[item.c].orEmpty() -> "exists"
                item.c == DirectCategory.APPS -> appSkipReason(item.app)
                else -> null
            }
            if (reason != null) why[item.k] = reason
        }
        val skip = why.keys.toList()
        val need = offer.items.filter { it.k !in why }.sumOf { it.s }
        val (free, _) = DeviceEnv.storage()
        // Keep some room: a phone with a completely full disk misbehaves.
        if (need > 0 && free - need < 200L * 1024 * 1024) return Accept(false, skip, free, "insufficient_storage", why)
        return Accept(true, skip, free, why = why)
    }

    /** Null when the app should come; otherwise why not (shown to the user on both phones). */
    private fun appSkipReason(app: AppMeta?): String? {
        if (app == null) return "invalid"
        if (app.minSdk > Build.VERSION.SDK_INT) return "old_android"
        val installed = apps.installedVersion(app.pkg) ?: return null
        return if (installed >= app.versionCode) "installed" else null
    }

    override fun begin(item: OfferItem): ItemWriter = when (item.c) {
        Category.CONTACTS, Category.CALENDARS -> SmallWriter(item)
        DirectCategory.APPS -> AppWriter(item)
        else -> MediaWriter(item)
    }

    private inner class MediaWriter(private val item: OfferItem) : ItemWriter {
        private val entry = media.createPending(item.c, item.n, item.m.orEmpty(), item.t, item.a)
        private var open = true
        private var written = 0L

        override fun write(b: ByteArray, off: Int, len: Int) {
            entry.output.write(b, off, len)
            written += len
        }

        override fun commit(sha256: String): ItemAck {
            entry.output.close()
            open = false
            media.publish(entry.uri, item.c, item.t)
            // Proof of storage: read the saved file back and hash it again.
            val (stored, size) = media.readBackSha256(entry.uri)
            if (stored != sha256 || size != written) {
                media.discard(entry.uri)
                return ItemAck(item.k, "failed", "checksum_mismatch")
            }
            return ItemAck(item.k, "saved")
        }

        override fun abort() {
            if (open) runCatching { entry.output.close() }
            open = false
            media.discard(entry.uri)
        }
    }

    private inner class SmallWriter(private val item: OfferItem) : ItemWriter {
        private val bytes = ByteArrayOutputStream(item.s.toInt().coerceIn(16, 1 shl 20))

        init {
            if (item.s > 64L * 1024 * 1024) throw IOException("export too large")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (bytes.size() + len > 64 * 1024 * 1024) throw IOException("export too large")
            bytes.write(b, off, len)
        }

        override fun commit(sha256: String): ItemAck {
            val text = String(bytes.toByteArray(), Charsets.UTF_8)
            if (item.c == Category.CONTACTS) {
                ContactsRepo(context).import(VCardCodec.parse(text), "merge")
            } else {
                CalendarRepo(context).import(ProtocolJson.decodeFromString(CalendarExport.serializer(), text).events)
            }
            return ItemAck(item.k, "saved")
        }

        override fun abort() {}
    }

    private inner class AppWriter(private val item: OfferItem) : ItemWriter {
        private val app = item.app ?: throw IOException("no app info")
        private val file = inbox.partFile(app.pkg, app.part)
        private val out = FileOutputStream(file)
        private var written = 0L

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
        }

        override fun commit(sha256: String): ItemAck {
            out.fd.sync()
            out.close()
            val (stored, size) = Hashing.sha256(file.inputStream())
            if (stored != sha256 || size != written) {
                file.delete()
                return ItemAck(item.k, "failed", "checksum_mismatch")
            }
            inbox.markReceived(app.pkg, app.label, app.versionCode, app.part, app.parts)
            return ItemAck(item.k, "saved")
        }

        override fun abort() {
            runCatching { out.close() }
            file.delete()
        }
    }

    companion object {
        val MEDIA = setOf(Category.PHOTOS, Category.VIDEOS, Category.MUSIC, Category.DOCUMENTS)
    }
}

/** Reading on this phone: originals through the catalog, apps from their installed files. */
class AndroidSource(private val open: (OfferItem) -> java.io.InputStream?) : DirectSource {
    override fun open(item: OfferItem): java.io.InputStream = try {
        open.invoke(item) ?: throw DirectSender.SourceFailure("not_found")
    } catch (e: FileNotFoundException) {
        throw DirectSender.SourceFailure("not_found")
    } catch (e: SecurityException) {
        throw DirectSender.SourceFailure("permission_denied")
    } catch (e: IOException) {
        throw DirectSender.SourceFailure("source_read_failed")
    }
}

/** Installer files of one app as offer items: one item per APK. */
fun appItems(app: app.switchflow.companion.data.InstalledApp, nextKey: () -> String): List<Pair<OfferItem, File>> =
    app.apks.mapIndexed { i, f ->
        OfferItem(
            k = nextKey(),
            c = DirectCategory.APPS,
            n = if (i == 0) "${app.label}.apk" else f.name,
            s = f.length(),
            m = "application/vnd.android.package-archive",
            app = AppMeta(app.pkg, app.label, app.versionCode, i, app.apks.size, app.minSdk),
        ) to f
    }
