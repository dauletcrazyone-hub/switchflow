package app.switchflow.companion.data

import java.io.ByteArrayOutputStream

/** Contact fields SwitchFlow moves between platforms. */
data class VCard(
    val formattedName: String = "",
    val familyName: String = "",
    val givenName: String = "",
    val middleName: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val phones: List<Typed> = emptyList(),
    val emails: List<Typed> = emptyList(),
    val organization: String = "",
    val title: String = "",
    val addresses: List<Address> = emptyList(),
    val note: String = "",
    val birthday: String = "",
    val urls: List<String> = emptyList(),
) {
    /** The name to show and to match duplicates on. */
    val displayName: String
        get() = formattedName.ifBlank {
            listOf(prefix, givenName, middleName, familyName, suffix).filter { it.isNotBlank() }.joinToString(" ")
        }.ifBlank { organization }.ifBlank { phones.firstOrNull()?.value ?: emails.firstOrNull()?.value ?: "" }

    fun isEmpty() = displayName.isBlank() && phones.isEmpty() && emails.isEmpty()
}

/** A value with a normalized type: CELL, HOME, WORK, MAIN, FAX, OTHER. */
data class Typed(val value: String, val type: String = "OTHER")

data class Address(
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
    val type: String = "HOME",
)

object VCardCodec {
    /**
     * The pick list for the PC: one entry per card `write` puts in the file, in the same order, so
     * entry `i` is the i-th card (the PC keeps only the chosen ones). Name plus a phone or e-mail
     * to tell apart people with the same name.
     */
    fun entries(cards: List<VCard>): List<app.switchflow.companion.net.Entry> =
        cards.filter { !it.isEmpty() }.mapIndexed { i, c ->
            app.switchflow.companion.net.Entry(i.toString(), c.displayName, c.phones.firstOrNull()?.value ?: c.emails.firstOrNull()?.value ?: "")
        }

    // ------------------------------------------------------------ writing

    fun write(cards: List<VCard>): String = buildString {
        for (c in cards) {
            if (c.isEmpty()) continue
            line("BEGIN:VCARD")
            line("VERSION:3.0")
            line("FN:" + esc(c.displayName))
            line("N:" + listOf(c.familyName, c.givenName, c.middleName, c.prefix, c.suffix).joinToString(";") { esc(it) })
            for (p in c.phones) line("TEL;TYPE=${p.type}:" + esc(p.value))
            for (e in c.emails) line("EMAIL;TYPE=INTERNET,${e.type}:" + esc(e.value))
            if (c.organization.isNotBlank()) line("ORG:" + esc(c.organization))
            if (c.title.isNotBlank()) line("TITLE:" + esc(c.title))
            for (a in c.addresses) {
                line("ADR;TYPE=${a.type}:;;" + listOf(a.street, a.city, a.region, a.postalCode, a.country).joinToString(";") { esc(it) })
            }
            if (c.birthday.isNotBlank()) line("BDAY:" + c.birthday)
            for (u in c.urls) line("URL:" + esc(u))
            if (c.note.isNotBlank()) line("NOTE:" + esc(c.note))
            line("END:VCARD")
        }
    }

    private fun StringBuilder.line(content: String) {
        // Fold at 75 octets (RFC 6350 §3.2) without splitting UTF-8 sequences.
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) {
            append(content).append("\r\n")
            return
        }
        var first = true
        var current = StringBuilder()
        var size = 0
        val limit = { if (first) 75 else 74 }
        for (ch in content) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (size + n > limit()) {
                if (!first) append(' ')
                append(current).append("\r\n")
                current = StringBuilder()
                size = 0
                first = false
            }
            current.append(ch)
            size += n
        }
        if (!first) append(' ')
        append(current).append("\r\n")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace(";", "\\;").replace(",", "\\,")

    // ------------------------------------------------------------ reading

    fun parse(text: String): List<VCard> {
        val lines = unfold(text)
        val out = mutableListOf<VCard>()
        var cur: Builder? = null
        for (raw in lines) {
            if (raw.isBlank()) continue
            val colon = findColon(raw)
            if (colon < 0) continue
            val head = raw.substring(0, colon)
            var value = raw.substring(colon + 1)
            val parts = head.split(';')
            // iOS groups properties: "item1.EMAIL" → "EMAIL".
            val name = parts[0].substringAfterLast('.').uppercase()
            val params = parts.drop(1).map { it.trim() }
            when (name) {
                "BEGIN" -> if (value.trim().equals("VCARD", true)) cur = Builder()
                "END" -> if (value.trim().equals("VCARD", true)) {
                    cur?.build()?.takeIf { !it.isEmpty() }?.let(out::add)
                    cur = null
                }
                else -> {
                    val b = cur ?: continue
                    if (params.any { it.equals("ENCODING=QUOTED-PRINTABLE", true) || it.equals("QUOTED-PRINTABLE", true) }) {
                        val charset = params.firstOrNull { it.startsWith("CHARSET=", true) }?.substringAfter('=') ?: "UTF-8"
                        value = decodeQuotedPrintable(value, charset)
                    }
                    b.add(name, types(params), value)
                }
            }
        }
        return out
    }

    /** Joins folded lines (CRLF followed by space/tab) and quoted-printable soft breaks. */
    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + l.substring(1)
            } else if (out.isNotEmpty() && out.last().endsWith("=") && out.last().contains("QUOTED-PRINTABLE", true)) {
                out[out.size - 1] = out.last().dropLast(1) + l
            } else {
                out.add(l)
            }
        }
        return out
    }

    /** First ':' that is not inside a quoted parameter value. */
    private fun findColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun types(params: List<String>): List<String> = params.flatMap { p ->
        val v = if (p.contains('=')) {
            if (!p.substringBefore('=').equals("TYPE", true)) return@flatMap emptyList()
            p.substringAfter('=')
        } else p // vCard 2.1 bare types: TEL;CELL:
        v.trim('"').split(',').map { it.trim().uppercase() }
    }

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Splits on unescaped [sep]. */
    private fun split(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == sep) {
                out.add(cur.toString())
                cur.clear()
            } else cur.append(c)
            i++
        }
        out.add(cur.toString())
        return out.map(::unesc)
    }

    private fun decodeQuotedPrintable(s: String, charset: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.write(v)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code and 0xff)
            i++
        }
        return runCatching { String(bytes.toByteArray(), charset(charset)) }.getOrElse { String(bytes.toByteArray(), Charsets.UTF_8) }
    }

    private fun normalizeType(types: List<String>, allowed: List<String>, default: String): String =
        types.firstOrNull { it in allowed } ?: if ("IPHONE" in types || "MOBILE" in types) "CELL" else default

    private class Builder {
        var fn = ""
        var n = listOf<String>()
        val phones = mutableListOf<Typed>()
        val emails = mutableListOf<Typed>()
        var org = ""
        var title = ""
        val addresses = mutableListOf<Address>()
        var note = ""
        var bday = ""
        val urls = mutableListOf<String>()

        fun add(name: String, types: List<String>, value: String) {
            when (name) {
                "FN" -> fn = unesc(value).trim()
                "N" -> n = split(value, ';')
                "TEL" -> unesc(value).trim().takeIf { it.isNotEmpty() }?.let {
                    phones += Typed(it, normalizeType(types, listOf("CELL", "HOME", "WORK", "MAIN", "FAX"), "OTHER"))
                }
                "EMAIL" -> unesc(value).trim().takeIf { it.isNotEmpty() }?.let {
                    emails += Typed(it, normalizeType(types, listOf("HOME", "WORK"), "OTHER"))
                }
                "ORG" -> org = split(value, ';').filter { it.isNotBlank() }.joinToString(", ")
                "TITLE" -> title = unesc(value).trim()
                "ADR" -> {
                    val p = split(value, ';') + List(7) { "" }
                    val a = Address(p[2], p[3], p[4], p[5], p[6], normalizeType(types, listOf("HOME", "WORK"), "OTHER"))
                    if (listOf(a.street, a.city, a.region, a.postalCode, a.country).any { it.isNotBlank() }) addresses += a
                }
                "NOTE" -> note = unesc(value)
                "BDAY" -> bday = value.trim()
                "URL" -> unesc(value).trim().takeIf { it.isNotEmpty() }?.let { urls += it }
            }
        }

        fun build(): VCard {
            val parts = n + List(5) { "" }
            return VCard(
                formattedName = fn,
                familyName = parts[0].trim(),
                givenName = parts[1].trim(),
                middleName = parts[2].trim(),
                prefix = parts[3].trim(),
                suffix = parts[4].trim(),
                phones = phones.distinctBy { normalizePhone(it.value) },
                emails = emails.distinctBy { it.value.lowercase() },
                organization = org,
                title = title,
                addresses = addresses,
                note = note,
                birthday = bday,
                urls = urls,
            )
        }
    }

    /** Digits only, last 10 — matches "+7 701 123-45-67" with "87011234567". */
    fun normalizePhone(p: String): String = p.filter { it.isDigit() }.takeLast(10)
}
