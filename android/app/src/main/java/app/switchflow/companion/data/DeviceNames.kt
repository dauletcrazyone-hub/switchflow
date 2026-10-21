package app.switchflow.companion.data

/**
 * Marketing names for model codes. Android reports Samsung phones as "SM-A256E" and has no
 * public API for "Galaxy A25 5G"; the user-set device name ("Galaxy s23") may be anything,
 * so it must never be shown as the model.
 */
object DeviceNames {
    // Keyed by the code without the region suffix: "SM-A256E" → "SM-A256".
    private val samsung = mapOf(
        "SM-S901" to "Galaxy S22", "SM-S906" to "Galaxy S22+", "SM-S908" to "Galaxy S22 Ultra",
        "SM-S911" to "Galaxy S23", "SM-S916" to "Galaxy S23+", "SM-S918" to "Galaxy S23 Ultra", "SM-S711" to "Galaxy S23 FE",
        "SM-S921" to "Galaxy S24", "SM-S926" to "Galaxy S24+", "SM-S928" to "Galaxy S24 Ultra", "SM-S721" to "Galaxy S24 FE",
        "SM-S931" to "Galaxy S25", "SM-S936" to "Galaxy S25+", "SM-S938" to "Galaxy S25 Ultra", "SM-S937" to "Galaxy S25 Edge",
        "SM-S731" to "Galaxy S25 FE",
        "SM-G990" to "Galaxy S21 FE", "SM-G991" to "Galaxy S21", "SM-G996" to "Galaxy S21+", "SM-G998" to "Galaxy S21 Ultra",
        "SM-F936" to "Galaxy Z Fold4", "SM-F946" to "Galaxy Z Fold5", "SM-F956" to "Galaxy Z Fold6", "SM-F966" to "Galaxy Z Fold7",
        "SM-F721" to "Galaxy Z Flip4", "SM-F731" to "Galaxy Z Flip5", "SM-F741" to "Galaxy Z Flip6", "SM-F766" to "Galaxy Z Flip7",
        "SM-A125" to "Galaxy A12", "SM-A127" to "Galaxy A12", "SM-A135" to "Galaxy A13", "SM-A136" to "Galaxy A13 5G",
        "SM-A145" to "Galaxy A14", "SM-A146" to "Galaxy A14 5G", "SM-A155" to "Galaxy A15", "SM-A156" to "Galaxy A15 5G",
        "SM-A165" to "Galaxy A16", "SM-A166" to "Galaxy A16 5G",
        "SM-A045" to "Galaxy A04", "SM-A047" to "Galaxy A04s", "SM-A055" to "Galaxy A05", "SM-A057" to "Galaxy A05s",
        "SM-A065" to "Galaxy A06", "SM-A207" to "Galaxy A20s", "SM-A217" to "Galaxy A21s",
        "SM-A225" to "Galaxy A22", "SM-A226" to "Galaxy A22 5G", "SM-A235" to "Galaxy A23", "SM-A236" to "Galaxy A23 5G",
        "SM-A245" to "Galaxy A24", "SM-A255" to "Galaxy A25", "SM-A256" to "Galaxy A25 5G", "SM-A266" to "Galaxy A26 5G",
        "SM-A325" to "Galaxy A32", "SM-A326" to "Galaxy A32 5G", "SM-A336" to "Galaxy A33 5G", "SM-A346" to "Galaxy A34 5G",
        "SM-A356" to "Galaxy A35 5G", "SM-A366" to "Galaxy A36 5G",
        "SM-A515" to "Galaxy A51", "SM-A525" to "Galaxy A52", "SM-A526" to "Galaxy A52 5G", "SM-A528" to "Galaxy A52s 5G",
        "SM-A536" to "Galaxy A53 5G", "SM-A546" to "Galaxy A54 5G", "SM-A556" to "Galaxy A55 5G", "SM-A566" to "Galaxy A56 5G",
        "SM-A715" to "Galaxy A71", "SM-A725" to "Galaxy A72", "SM-A736" to "Galaxy A73 5G",
        "SM-M135" to "Galaxy M13", "SM-M146" to "Galaxy M14 5G", "SM-M156" to "Galaxy M15 5G", "SM-M346" to "Galaxy M34 5G",
        "SM-M356" to "Galaxy M35 5G",
    )

    /** "SM-A256E" → "Galaxy A25 5G"; already-friendly names ("Pixel 8") pass through; unknown codes stay as they are. */
    fun marketingName(manufacturer: String, modelCode: String): String {
        val code = modelCode.trim()
        if (manufacturer.equals("samsung", ignoreCase = true) && code.startsWith("SM-", ignoreCase = true) && code.length >= 7) {
            samsung[code.substring(0, 7).uppercase()]?.let { return it }
        }
        return code
    }
}
