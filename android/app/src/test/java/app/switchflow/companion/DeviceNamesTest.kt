package app.switchflow.companion

import app.switchflow.companion.data.DeviceNames
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNamesTest {
    @Test
    fun samsungCodesBecomeMarketingNames() {
        // The phone this was found on: SM-A256E whose user-set name is "Galaxy s23".
        assertEquals("Galaxy A25 5G", DeviceNames.marketingName("samsung", "SM-A256E"))
        // Two-phone test: Galaxy A10 (Android 9) showed up on the PC as "SM-A105F".
        assertEquals("Galaxy A10", DeviceNames.marketingName("samsung", "SM-A105F"))
        assertEquals("Galaxy S22", DeviceNames.marketingName("samsung", "SM-S901B"))
        assertEquals("Galaxy A20s", DeviceNames.marketingName("samsung", "SM-A207F"))
        assertEquals("Galaxy S23", DeviceNames.marketingName("Samsung", "SM-S911B"))
    }

    @Test
    fun othersPassThrough() {
        assertEquals("Pixel 8", DeviceNames.marketingName("Google", "Pixel 8"))
        assertEquals("SM-X999", DeviceNames.marketingName("samsung", "SM-X999"))
        assertEquals("23129RAA4G", DeviceNames.marketingName("Xiaomi", "23129RAA4G"))
    }
}
