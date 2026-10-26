package app.switchflow.companion

import app.switchflow.companion.data.Address
import app.switchflow.companion.data.Typed
import app.switchflow.companion.data.VCard
import app.switchflow.companion.data.VCardCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardTest {
    @Test
    fun roundTripKeepsFields() {
        val card = VCard(
            formattedName = "Алия Нурланова",
            familyName = "Нурланова",
            givenName = "Алия",
            phones = listOf(Typed("+7 701 123-45-67", "CELL"), Typed("8 727 000 00 00", "WORK")),
            emails = listOf(Typed("aliya@example.kz", "HOME")),
            organization = "Компания; ТОО",
            title = "Инженер",
            addresses = listOf(Address(street = "пр. Абая, 1", city = "Алматы", country = "Казахстан")),
            note = "Первая строка\nВторая, с запятой",
            birthday = "1990-05-17",
        )
        val text = VCardCodec.write(listOf(card))
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue("lines are folded at 75 octets", text.split("\r\n").all { it.toByteArray().size <= 75 })
        val back = VCardCodec.parse(text).single()
        assertEquals(card.displayName, back.displayName)
        assertEquals(card.phones, back.phones)
        assertEquals(card.emails, back.emails)
        assertEquals(card.organization, back.organization)
        assertEquals(card.note, back.note)
        assertEquals(card.addresses.single().city, back.addresses.single().city)
        assertEquals(card.birthday, back.birthday)
    }

    @Test
    fun parsesIosExport() {
        // Shape produced by CNContactVCardSerialization: grouped properties and type params.
        val ios = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Apple Inc.//iPhone OS 18.0//EN
            N:Smith;John;;;
            FN:John Smith
            item1.EMAIL;type=INTERNET;type=pref:john@example.com
            item1.X-ABLabel:_${'$'}!<Other>!${'$'}_
            TEL;type=CELL;type=VOICE;type=pref:+1 (555) 010-0000
            TEL;type=IPHONE;type=CELL;type=VOICE:+15550100001
            END:VCARD
        """.trimIndent()
        val c = VCardCodec.parse(ios).single()
        assertEquals("John Smith", c.displayName)
        assertEquals("John", c.givenName)
        assertEquals(listOf("john@example.com"), c.emails.map { it.value })
        assertEquals(2, c.phones.size)
        assertEquals("CELL", c.phones[0].type)
    }

    @Test
    fun parsesAndroidVcard21QuotedPrintable() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D0=98=D0=B2=D0=B0=D0=BD=D0=BE=D0=B2;=D0=98=D0=B2=D0=B0=D0=BD;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=D0=98=D0=B2=D0=B0=D0=BD =D0=98=D0=B2=D0=B0=D0=BD=D0=BE=D0=B2\r\n" +
            "TEL;CELL:+77011234567\r\nTEL;HOME:+7 (701) 123-45-67\r\nEND:VCARD\r\n"
        val c = VCardCodec.parse(v21).single()
        assertEquals("Иван Иванов", c.displayName)
        assertEquals("Иванов", c.familyName)
        // Same number written two ways is kept once.
        assertEquals(1, c.phones.size)
    }

    @Test
    fun pickListEntriesFollowTheCardsInTheFile() {
        val cards = listOf(
            VCard(formattedName = "Алия", phones = listOf(Typed("+77030000001", "CELL"))),
            VCard(), // empty: not written, so it gets no entry either
            VCard(formattedName = "Гульнар", emails = listOf(Typed("g@example.com"))),
        )
        val entries = VCardCodec.entries(cards)
        assertEquals(listOf("0" to "Алия", "1" to "Гульнар"), entries.map { it.id to it.name })
        assertEquals(listOf("+77030000001", "g@example.com"), entries.map { it.detail })
        val inFile = VCardCodec.parse(VCardCodec.write(cards))
        assertEquals(entries.map { it.name }, inFile.map { it.displayName })
    }

    @Test
    fun skipsEmptyCardsAndNormalizesPhones() {
        assertTrue(VCardCodec.parse("BEGIN:VCARD\nVERSION:3.0\nEND:VCARD\n").isEmpty())
        assertEquals(VCardCodec.normalizePhone("+7 (701) 123-45-67"), VCardCodec.normalizePhone("87011234567"))
    }
}
