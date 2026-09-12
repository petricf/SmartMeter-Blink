package de.smartmeter.blink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MeterProfileJsonTest
{
    private val assetFile = File("src/main/assets/meter_profiles.json")

    @Test
    fun predefinedAssetIsValidJsonAndParses()
    {
        assertTrue("asset file exists", assetFile.exists())
        val profiles = MeterProfile.parseProfilesJson(assetFile.readText())
        assertTrue("profiles non-empty", profiles.isNotEmpty())
        assertEquals("unique ids", profiles.size, profiles.map { it.id }.toSet().size)
    }

    @Test
    fun predefinedAssetContainsTheE320Preset()
    {
        val profiles = MeterProfile.parseProfilesJson(assetFile.readText())
        val e320 = profiles.firstOrNull { it.id == "e320" }
        assertTrue("e320 preset present", e320 != null)
        e320?.let {
            assertEquals("Landis+Gyr/EYKON E320", it.name)
            assertEquals(4, it.pinLength)
            assertEquals(180, it.timing.pulseLengthMs)
            assertEquals(320, it.timing.pulseGapMs)
            assertEquals(3400, it.timing.digitWaitMs)
            assertEquals(6000, it.timing.longPulseMs)
            assertEquals(8, it.opticalScrolls)
            assertEquals(9, it.pinScrolls)
        }
    }

    @Test
    fun fromJsonClampsOutOfRangeValues()
    {
        val raw = """[{ "id": "x", "name": "X", "pinLength": 99,
            "pulseLengthMs": 1, "digitWaitMs": 999999 }]"""
        val p = MeterProfile.parseProfilesJson(raw).first()
        assertEquals(8, p.pinLength)
        assertEquals(50, p.timing.pulseLengthMs)
        assertEquals(15000, p.timing.digitWaitMs)
    }

    @Test
    fun singleObjectIsAlsoAccepted()
    {
        val raw = """{ "id": "y", "name": "Y", "pinLength": 6,
            "opticalScrolls": 7, "pinScrolls": 3 }"""
        val p = MeterProfile.parseProfilesJson(raw).single()
        assertEquals("y", p.id)
        assertEquals(6, p.pinLength)
        assertEquals(7, p.opticalScrolls)
        assertEquals(3, p.pinScrolls)
    }
}