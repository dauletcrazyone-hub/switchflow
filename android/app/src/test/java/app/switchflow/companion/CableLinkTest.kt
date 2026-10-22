package app.switchflow.companion

import app.switchflow.companion.net.CableLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CableLinkTest {
    // Same rule as desktop cable.rs: one frame per USB transfer, never a multiple of the packet size.
    @Test
    fun dataChunksFitOneReadAndAvoidPacketBoundaries() {
        for (total in 1..40_000) {
            var left = total
            var sent = 0
            while (left > 0) {
                val n = CableLink.chunkLength(left)
                assertTrue(n in 1..left)
                assertTrue(9 + n <= 16_384)
                assertNotEquals("total $total", 0, (9 + n) % 64)
                left -= n
                sent += n
            }
            assertEquals(total, sent)
        }
    }
}
