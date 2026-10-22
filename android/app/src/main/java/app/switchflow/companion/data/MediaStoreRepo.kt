package app.switchflow.companion.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.switchflow.companion.net.Album
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.SourceItem
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Reads the phone's media library and saves received media through MediaStore. */
class MediaStoreRepo(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    data class Listing(val items: List<SourceItem>, val uris: Map<String, Uri>, val albums: List<Album>)

    /**
     * Android 9 has no RELATIVE_PATH / IS_PENDING / Downloads collection: received files are written
     * to their public folder by path (WRITE_EXTERNAL_STORAGE) and registered in MediaStore by DATA.
     */
    private val legacy = Build.VERSION.SDK_INT < 29

    private fun legacyCollection(category: String): Uri = when (category) {
        Category.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        Category.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        Category.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Files.getContentUri("external")
    }

    private fun collection(category: String): Uri = if (legacy) legacyCollection(category) else when (category) {
        Category.PHOTOS -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        Category.VIDEOS -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        Category.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    private fun writeCollection(category: String): Uri = if (legacy) legacyCollection(category) else when (category) {
        Category.PHOTOS -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Category.VIDEOS -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Category.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /** Lists photos, videos or music the app is allowed to read. Ids look like "photos:42". */
    fun list(category: String): Listing {
        val isAudio = category == Category.MUSIC
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (!isAudio) {
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.BUCKET_ID)
                add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            }
        }.toTypedArray()
        val selection = if (isAudio) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null
        val base = collection(category)
        val items = mutableListOf<SourceItem>()
        val uris = mutableMapOf<String, Uri>()
        val albums = linkedMapOf<String, Triple<String, Long, Long>>()
        resolver.query(base, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val bucketCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val rowId = c.getLong(idCol)
                // MediaStore leaves SIZE empty for files indexed by the legacy scanner; ask the file itself.
                val size = if (!c.isNull(sizeCol) && c.getLong(sizeCol) > 0) c.getLong(sizeCol) else statSize(ContentUris.withAppendedId(base, rowId))
                val name = c.getString(nameCol) ?: "file_$rowId"
                val id = "$category:$rowId"
                val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else null
                val bucketName = if (bucketNameCol >= 0) c.getString(bucketNameCol)?.takeIf { it.isNotBlank() } else null
                // Files in the storage root have no folder name; leave them out of the album list
                // instead of showing a numeric bucket id. "All albums" still includes them.
                val bucket = if (bucketCol >= 0 && bucketName != null) c.getString(bucketCol) else null
                if (bucket != null) {
                    val prev = albums[bucket]
                    albums[bucket] = Triple(bucketName!!, (prev?.second ?: 0) + 1, (prev?.third ?: 0) + size)
                }
                items += SourceItem(
                    id = id,
                    category = category,
                    name = name,
                    size = size,
                    sizeExact = size > 0,
                    mime = c.getString(mimeCol) ?: guessMime(name),
                    createdMs = taken ?: c.getLong(addedCol) * 1000,
                    modifiedMs = c.getLong(modCol) * 1000,
                    albumIds = listOfNotNull(bucket),
                )
                uris[id] = ContentUris.withAppendedId(base, rowId)
            }
        }
        return Listing(items, uris, albums.map { (id, v) -> Album(id, v.first, v.second, v.third) })
    }

    private fun statSize(uri: Uri): Long =
        runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it >= 0 } ?: 0L

    /** A pending MediaStore entry: invisible to other apps until [publish]. */
    class PendingEntry(val uri: Uri, val output: OutputStream)

    fun relativePath(category: String, album: String?): String {
        val safeAlbum = album?.replace(Regex("""[\\/:*?"<>|]"""), "_")?.trim()?.take(60)?.ifBlank { null }
        return when (category) {
            Category.PHOTOS -> if (safeAlbum != null) "Pictures/SwitchFlow/$safeAlbum" else "DCIM/SwitchFlow"
            Category.VIDEOS -> if (safeAlbum != null) "Movies/SwitchFlow/$safeAlbum" else "DCIM/SwitchFlow"
            Category.MUSIC -> "Music/SwitchFlow"
            else -> "Download/SwitchFlow"
        }
    }

    fun createPending(category: String, name: String, mime: String, createdMs: Long?, album: String?): PendingEntry {
        val safeMime = sanitizeMime(category, mime, name)
        if (legacy) return createLegacy(category, name, safeMime, createdMs, album)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(category, album))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (createdMs != null && (category == Category.PHOTOS || category == Category.VIDEOS)) {
                put(MediaStore.MediaColumns.DATE_TAKEN, createdMs)
            }
        }
        val uri = resolver.insert(writeCollection(category), values) ?: throw IOException("MediaStore insert failed")
        val out = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            throw IOException("cannot open output")
        }
        return PendingEntry(uri, out)
    }

    @Suppress("DEPRECATION") // DATA and the public storage root are the only way to place files before Android 10
    private fun legacyFolder(category: String, album: String?) = File(Environment.getExternalStorageDirectory(), relativePath(category, album))

    @Suppress("DEPRECATION")
    private fun createLegacy(category: String, name: String, mime: String, createdMs: Long?, album: String?): PendingEntry {
        val dir = legacyFolder(category, album)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create folder")
        // Never overwrite: a different file with the same name gets " (1)", " (2)"… like MediaStore does on Android 10+.
        val file = uniqueFile(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (createdMs != null && (category == Category.PHOTOS || category == Category.VIDEOS)) {
                put(MediaStore.MediaColumns.DATE_TAKEN, createdMs)
            }
        }
        val uri = resolver.insert(writeCollection(category), values) ?: run {
            file.delete()
            throw IOException("MediaStore insert failed")
        }
        val out = resolver.openOutputStream(uri, "w") ?: run {
            discard(uri)
            throw IOException("cannot open output")
        }
        return PendingEntry(uri, out)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var n = 1
        while (!candidate.createNewFile()) candidate = File(dir, "$base (${n++})$ext")
        return candidate
    }

    @Suppress("DEPRECATION")
    private fun legacyPath(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun publish(uri: Uri, category: String, createdMs: Long?) {
        if (legacy) {
            // The row exists from the start; a scan fills in size, dimensions and EXIF dates.
            legacyPath(uri)?.let { MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
        } else {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        // Publishing re-scans the file and resets DATE_TAKEN from EXIF; files without EXIF dates
        // (screenshots, messenger images) would lose their original date, so try to write it back.
        // Verified on a Galaxy A20s (Android 11): MediaProvider silently ignores this update, so there
        // such files show the restore time. Photos with EXIF keep their date (bytes are unchanged).
        if (createdMs != null && (category == Category.PHOTOS || category == Category.VIDEOS)) {
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, createdMs) }, null, null)
            }
        }
    }

    fun discard(uri: Uri) {
        // Only ever called on a file SwitchFlow itself just created and could not verify.
        val path = if (legacy) legacyPath(uri) else null
        runCatching { resolver.delete(uri, null, null) }
        path?.let { runCatching { File(it).delete() } }
    }

    /** Reads the saved file back from MediaStore and hashes it: proof the bytes landed. */
    fun readBackSha256(uri: Uri): Pair<String, Long> =
        Hashing.sha256(resolver.openInputStream(uri) ?: throw java.io.IOException("cannot reopen saved file"))

    /**
     * True when a file with the same name and size already sits in the folder SwitchFlow
     * writes to (e.g. from an earlier, interrupted transfer). Only our own files are visible
     * here without broad read permissions, which is exactly the set that can be duplicated.
     */
    fun existsInTarget(category: String, name: String, size: Long, album: String?): Boolean {
        if (legacy) {
            @Suppress("DEPRECATION")
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.DATA} LIKE ?"
            val args = arrayOf(name, size.toString(), legacyFolder(category, album).absolutePath + "/%")
            return resolver.query(writeCollection(category), arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { it.count > 0 } ?: false
        }
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(name, size.toString(), relativePath(category, album) + "%")
        return resolver.query(writeCollection(category), arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { it.count > 0 } ?: false
    }

    companion object {
        fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: when (ext) {
                    "heic" -> "image/heic"
                    "heif" -> "image/heif"
                    "mov" -> "video/quicktime"
                    else -> "application/octet-stream"
                }
        }

        /** MediaStore rejects a MIME type that does not match the collection. */
        fun sanitizeMime(category: String, mime: String, name: String): String {
            val m = mime.ifBlank { guessMime(name) }
            val prefix = when (category) {
                Category.PHOTOS -> "image/"
                Category.VIDEOS -> "video/"
                Category.MUSIC -> "audio/"
                else -> return m
            }
            if (m.startsWith(prefix)) return m
            val guessed = guessMime(name)
            if (guessed.startsWith(prefix)) return guessed
            return when (category) {
                Category.PHOTOS -> "image/jpeg"
                Category.VIDEOS -> "video/mp4"
                else -> "audio/mpeg"
            }
        }
    }
}
