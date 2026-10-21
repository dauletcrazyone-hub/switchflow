package app.switchflow.companion.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.switchflow.companion.data.CalendarExport
import app.switchflow.companion.data.CalendarRepo
import app.switchflow.companion.data.ContactsRepo
import app.switchflow.companion.data.MediaStoreRepo
import app.switchflow.companion.data.VCardCodec
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.CategoryInventory
import app.switchflow.companion.net.Permission
import app.switchflow.companion.net.ProtocolJson
import app.switchflow.companion.net.SourceItem
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * What this phone offers as a source. Media is streamed straight from MediaStore;
 * contacts and calendars are exported once into the app's private cache and deleted
 * when the session ends.
 */
class SourceCatalog(private val context: Context) {
    private val media = MediaStoreRepo(context)
    private val uris = ConcurrentHashMap<String, Uri>()
    private val files = ConcurrentHashMap<String, File>()
    private val exportDir get() = File(context.cacheDir, "sf-export").apply { mkdirs() }

    @Volatile var pickedDocuments: List<Uri> = emptyList()

    fun scan(category: String, permission: String): CategoryInventory {
        if (category == Category.DOCUMENTS) return scanDocuments()
        if (permission != Permission.GRANTED && permission != Permission.LIMITED) {
            return CategoryInventory(category, permission, 0, 0)
        }
        return when (category) {
            Category.PHOTOS, Category.VIDEOS, Category.MUSIC -> {
                val l = media.list(category)
                uris.putAll(l.uris)
                CategoryInventory(category, permission, l.items.size.toLong(), l.items.sumOf { it.size }, if (category == Category.MUSIC) emptyList() else l.albums, l.items)
            }
            Category.CONTACTS -> {
                val cards = ContactsRepo(context).exportAll()
                val file = File(exportDir, "contacts.vcf").apply { writeText(VCardCodec.write(cards), Charsets.UTF_8) }
                files[Category.CONTACTS] = file
                val item = SourceItem(Category.CONTACTS, category, "contacts.vcf", file.length(), mime = "text/vcard", itemCount = cards.size)
                CategoryInventory(category, permission, cards.size.toLong(), file.length(), items = if (cards.isEmpty()) emptyList() else listOf(item))
            }
            Category.CALENDARS -> {
                val events = CalendarRepo(context).exportAll()
                val json = ProtocolJson.encodeToString(CalendarExport.serializer(), CalendarExport(events = events))
                val file = File(exportDir, "calendar-events.json").apply { writeText(json, Charsets.UTF_8) }
                files[Category.CALENDARS] = file
                val item = SourceItem(Category.CALENDARS, category, "calendar-events.json", file.length(), mime = "application/json", itemCount = events.size)
                CategoryInventory(category, permission, events.size.toLong(), file.length(), items = if (events.isEmpty()) emptyList() else listOf(item))
            }
            else -> CategoryInventory(category, Permission.UNAVAILABLE, 0, 0)
        }
    }

    private fun scanDocuments(): CategoryInventory {
        val items = mutableListOf<SourceItem>()
        pickedDocuments.forEachIndexed { i, uri ->
            var name = "document_$i"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val id = "${Category.DOCUMENTS}:$i"
            uris[id] = uri
            items += SourceItem(
                id = id,
                category = Category.DOCUMENTS,
                name = name,
                size = maxOf(size, 0),
                sizeExact = size >= 0,
                mime = context.contentResolver.getType(uri) ?: MediaStoreRepo.guessMime(name),
            )
        }
        // Nothing picked is not a refused permission: the PC shows "no files chosen".
        val note = if (pickedDocuments.isEmpty()) "not_picked" else null
        return CategoryInventory(Category.DOCUMENTS, Permission.GRANTED, items.size.toLong(), items.sumOf { it.size }, items = items, note = note)
    }

    /** Opens the original for reading. Never opens anything for writing. */
    fun open(sourceId: String): InputStream {
        files[sourceId]?.let { return it.inputStream() }
        val uri = uris[sourceId] ?: throw FileNotFoundException(sourceId)
        return context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(sourceId)
    }

    /** Deletes exported personal data (contacts.vcf, calendar-events.json). */
    fun clearExports() {
        files.clear()
        exportDir.listFiles()?.forEach { it.delete() }
    }
}
