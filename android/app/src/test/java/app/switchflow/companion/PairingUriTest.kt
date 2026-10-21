package app.switchflow.companion

import app.switchflow.companion.data.Rfc5545Duration
import app.switchflow.companion.net.PairingUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingUriTest {
    private val fp = "ab".repeat(32)

    @Test
    fun parsesDesktopQr() {
        // Exactly what desktop/src-tauri/src/core.rs pairing_uri() produces.
        val uri = "switchflow://pair?v=1&hosts=192.168.1.24,10.0.0.5,127.0.0.1&port=47820&fp=$fp&s=Zm9vYmFyYmF6cXV4MTIz&n=%D0%9F%D0%9A%201"
        val t = PairingUri.parse(uri)!!
        assertEquals(listOf("192.168.1.24", "10.0.0.5", "127.0.0.1"), t.hosts)
        assertEquals(47820, t.port)
        assertEquals(fp, t.fingerprint)
        assertEquals("Zm9vYmFyYmF6cXV4MTIz", t.secret)
        assertEquals("ПК 1", t.desktopName)
    }

    @Test
    fun rejectsForeignOrBrokenCodes() {
        assertNull(PairingUri.parse("https://example.com"))
        assertNull(PairingUri.parse("switchflow://pair?v=2&hosts=1.2.3.4&port=1&fp=$fp&s=Zm9vYmFyYmF6cXV4MTIz"))
        assertNull(PairingUri.parse("switchflow://pair?v=1&hosts=evil.com&port=47820&fp=$fp&s=Zm9vYmFyYmF6cXV4MTIz"))
        assertNull(PairingUri.parse("switchflow://pair?v=1&hosts=1.2.3.4&port=47820&fp=short&s=Zm9vYmFyYmF6cXV4MTIz"))
        assertNull(PairingUri.parse("switchflow://pair?v=1&hosts=1.2.3.4&port=99999&fp=$fp&s=Zm9vYmFyYmF6cXV4MTIz"))
        assertNull(PairingUri.parse("switchflow://pair?v=1&hosts=999.2.3.4&port=47820&fp=$fp&s=Zm9vYmFyYmF6cXV4MTIz"))
    }

    @Test
    fun durations() {
        assertEquals(3_600_000L, Rfc5545Duration.toMillis("P3600S"))
        assertEquals(5_400_000L, Rfc5545Duration.toMillis("PT1H30M"))
        assertEquals(86_400_000L, Rfc5545Duration.toMillis("P1D"))
        assertEquals(3_600_000L, Rfc5545Duration.toMillis(Rfc5545Duration.fromMillis(3_600_000L)))
        assertNull(Rfc5545Duration.toMillis("garbage"))
    }
}
