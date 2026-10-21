package app.switchflow.companion.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import app.switchflow.companion.net.AckDetail

/** Contacts Provider ↔ vCard. Import never deletes or overwrites existing fields. */
class ContactsRepo(private val context: Context) {
    private val resolver get() = context.contentResolver

    private class Draft {
        var name = VCard()
        val phones = mutableListOf<Typed>()
        val emails = mutableListOf<Typed>()
        val addresses = mutableListOf<Address>()
        val urls = mutableListOf<String>()
        var org = ""
        var title = ""
        var note = ""
        var bday = ""
    }

    fun exportAll(): List<VCard> {
        val drafts = LinkedHashMap<Long, Draft>()
        val mimes = listOf(
            StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
            StructuredPostal.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE,
        )
        val projection = arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10)
        val sel = "${Data.MIMETYPE} IN (${mimes.joinToString { "?" }})"
        resolver.query(Data.CONTENT_URI, projection, sel, mimes.toTypedArray(), "${Data.CONTACT_ID} ASC")?.use { c ->
            fun s(i: Int) = c.getString(i)?.trim().orEmpty()
            while (c.moveToNext()) {
                val d = drafts.getOrPut(c.getLong(0)) { Draft() }
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> d.name = VCard(
                        formattedName = s(2), givenName = s(3), familyName = s(4), prefix = s(5), middleName = s(6), suffix = s(7),
                    )
                    Phone.CONTENT_ITEM_TYPE -> if (s(2).isNotEmpty()) d.phones += Typed(s(2), phoneType(c.getInt(3)))
                    Email.CONTENT_ITEM_TYPE -> if (s(2).isNotEmpty()) d.emails += Typed(s(2), emailType(c.getInt(3)))
                    Organization.CONTENT_ITEM_TYPE -> { d.org = s(2); d.title = s(5) }
                    StructuredPostal.CONTENT_ITEM_TYPE -> d.addresses += Address(
                        street = s(5), city = s(8), region = s(9), postalCode = s(10), country = s(11),
                        type = if (c.getInt(3) == StructuredPostal.TYPE_WORK) "WORK" else "HOME",
                    )
                    Note.CONTENT_ITEM_TYPE -> d.note = s(2)
                    Event.CONTENT_ITEM_TYPE -> if (c.getInt(3) == Event.TYPE_BIRTHDAY) d.bday = s(2)
                    Website.CONTENT_ITEM_TYPE -> if (s(2).isNotEmpty()) d.urls += s(2)
                }
            }
        }
        return drafts.values.map { d ->
            d.name.copy(
                phones = d.phones.distinctBy { VCardCodec.normalizePhone(it.value) },
                emails = d.emails.distinctBy { it.value.lowercase() },
                organization = d.org,
                title = d.title,
                addresses = d.addresses,
                note = d.note,
                birthday = d.bday,
                urls = d.urls,
            )
        }.filter { !it.isEmpty() }
    }

    private data class Existing(val rawContactId: Long, val phones: MutableSet<String>, val emails: MutableSet<String>)

    private fun normName(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** name → existing contacts with that name. */
    private fun index(): Map<String, List<Existing>> {
        val byContact = HashMap<Long, Pair<String, Existing>>()
        val mimes = arrayOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE)
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DISPLAY_NAME),
            "${Data.MIMETYPE} IN (?,?,?)", mimes, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val cid = c.getLong(0)
                val entry = byContact.getOrPut(cid) {
                    normName(c.getString(4).orEmpty()) to Existing(c.getLong(1), mutableSetOf(), mutableSetOf())
                }
                val v = c.getString(3).orEmpty()
                when (c.getString(2)) {
                    Phone.CONTENT_ITEM_TYPE -> VCardCodec.normalizePhone(v).takeIf { it.length >= 5 }?.let { entry.second.phones += it }
                    Email.CONTENT_ITEM_TYPE -> entry.second.emails += v.lowercase().trim()
                }
            }
        }
        return byContact.values.groupBy({ it.first }, { it.second })
    }

    /**
     * Imports cards with the chosen duplicate policy:
     * merge — add missing numbers/emails to the matching contact; skip — leave it; keepBoth — always insert.
     */
    fun import(cards: List<VCard>, policy: String): AckDetail {
        val existing = if (policy == "keepBoth") emptyMap() else index()
        var imported = 0L
        var merged = 0L
        var skipped = 0L
        val ops = ArrayList<ContentProviderOperation>()

        fun flush() {
            if (ops.isNotEmpty()) {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                ops.clear()
            }
        }

        for (card in cards) {
            val phones = card.phones.map { VCardCodec.normalizePhone(it.value) }.filter { it.length >= 5 }.toSet()
            val emails = card.emails.map { it.value.lowercase().trim() }.toSet()
            val match = existing[normName(card.displayName)]?.firstOrNull { e ->
                (phones.isEmpty() && emails.isEmpty()) || e.phones.any { it in phones } || e.emails.any { it in emails } ||
                    (e.phones.isEmpty() && e.emails.isEmpty())
            }
            if (match != null && policy != "keepBoth") {
                if (policy == "skip") {
                    skipped++
                    continue
                }
                val newPhones = card.phones.filter { VCardCodec.normalizePhone(it.value) !in match.phones }
                val newEmails = card.emails.filter { it.value.lowercase().trim() !in match.emails }
                for (p in newPhones) ops += dataOp(match.rawContactId, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to p.value, Phone.TYPE to phoneCode(p.type))
                for (e in newEmails) ops += dataOp(match.rawContactId, Email.CONTENT_ITEM_TYPE, Email.ADDRESS to e.value, Email.TYPE to emailCode(e.type))
                newPhones.forEach { match.phones += VCardCodec.normalizePhone(it.value) }
                newEmails.forEach { match.emails += it.value.lowercase().trim() }
                if (newPhones.isEmpty() && newEmails.isEmpty()) skipped++ else merged++
            } else {
                insert(card, ops)
                imported++
            }
            if (ops.size > 350) flush()
        }
        flush()
        return AckDetail(imported = imported, merged = merged, skipped = skipped)
    }

    private fun dataOp(rawId: Long, mime: String, vararg values: Pair<String, Any?>) =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValue(Data.RAW_CONTACT_ID, rawId)
            .withValue(Data.MIMETYPE, mime)
            .apply { values.forEach { (k, v) -> withValue(k, v) } }
            .build()

    private fun insert(card: VCard, ops: ArrayList<ContentProviderOperation>) {
        val back = ops.size
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        fun add(mime: String, vararg values: Pair<String, Any?>) {
            ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, back)
                .withValue(Data.MIMETYPE, mime)
                .apply { values.forEach { (k, v) -> withValue(k, v) } }
                .build()
        }
        add(
            StructuredName.CONTENT_ITEM_TYPE,
            StructuredName.DISPLAY_NAME to card.displayName,
            StructuredName.GIVEN_NAME to card.givenName.ifBlank { null },
            StructuredName.FAMILY_NAME to card.familyName.ifBlank { null },
            StructuredName.MIDDLE_NAME to card.middleName.ifBlank { null },
            StructuredName.PREFIX to card.prefix.ifBlank { null },
            StructuredName.SUFFIX to card.suffix.ifBlank { null },
        )
        card.phones.forEach { add(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to it.value, Phone.TYPE to phoneCode(it.type)) }
        card.emails.forEach { add(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to it.value, Email.TYPE to emailCode(it.type)) }
        if (card.organization.isNotBlank() || card.title.isNotBlank()) {
            add(Organization.CONTENT_ITEM_TYPE, Organization.COMPANY to card.organization, Organization.TITLE to card.title)
        }
        card.addresses.forEach {
            add(
                StructuredPostal.CONTENT_ITEM_TYPE,
                StructuredPostal.STREET to it.street, StructuredPostal.CITY to it.city, StructuredPostal.REGION to it.region,
                StructuredPostal.POSTCODE to it.postalCode, StructuredPostal.COUNTRY to it.country,
                StructuredPostal.TYPE to if (it.type == "WORK") StructuredPostal.TYPE_WORK else StructuredPostal.TYPE_HOME,
            )
        }
        if (card.note.isNotBlank()) add(Note.CONTENT_ITEM_TYPE, Note.NOTE to card.note)
        if (card.birthday.isNotBlank()) add(Event.CONTENT_ITEM_TYPE, Event.START_DATE to card.birthday, Event.TYPE to Event.TYPE_BIRTHDAY)
        card.urls.forEach { add(Website.CONTENT_ITEM_TYPE, Website.URL to it, Website.TYPE to Website.TYPE_OTHER) }
    }

    companion object {
        fun phoneType(code: Int) = when (code) {
            Phone.TYPE_MOBILE -> "CELL"
            Phone.TYPE_HOME -> "HOME"
            Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "WORK"
            Phone.TYPE_MAIN -> "MAIN"
            Phone.TYPE_FAX_HOME, Phone.TYPE_FAX_WORK -> "FAX"
            else -> "OTHER"
        }

        fun phoneCode(type: String) = when (type) {
            "CELL" -> Phone.TYPE_MOBILE
            "HOME" -> Phone.TYPE_HOME
            "WORK" -> Phone.TYPE_WORK
            "MAIN" -> Phone.TYPE_MAIN
            "FAX" -> Phone.TYPE_FAX_WORK
            else -> Phone.TYPE_OTHER
        }

        fun emailType(code: Int) = when (code) {
            Email.TYPE_HOME -> "HOME"
            Email.TYPE_WORK -> "WORK"
            else -> "OTHER"
        }

        fun emailCode(type: String) = when (type) {
            "HOME" -> Email.TYPE_HOME
            "WORK" -> Email.TYPE_WORK
            else -> Email.TYPE_OTHER
        }
    }
}
