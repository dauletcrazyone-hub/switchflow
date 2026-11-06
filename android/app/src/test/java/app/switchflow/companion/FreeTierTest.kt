package app.switchflow.companion

import app.switchflow.companion.billing.FreeTier
import app.switchflow.companion.net.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTierTest {
    @Test
    fun contactsCalendarsAndAFewPhotosAreFree() {
        assertFalse(FreeTier.needsPro(mapOf(Category.CONTACTS to 2000L, Category.CALENDARS to 300L)))
        assertFalse(FreeTier.needsPro(mapOf(Category.PHOTOS to 30L, Category.VIDEOS to 20L)))
    }

    @Test
    fun moreMediaOrOtherKindsNeedPro() {
        assertTrue(FreeTier.needsPro(mapOf(Category.PHOTOS to 40L, Category.VIDEOS to 11L)))
        assertTrue(FreeTier.needsPro(mapOf(Category.MUSIC to 1L)))
        assertTrue(FreeTier.needsPro(mapOf(Category.DOCUMENTS to 1L)))
    }
}
