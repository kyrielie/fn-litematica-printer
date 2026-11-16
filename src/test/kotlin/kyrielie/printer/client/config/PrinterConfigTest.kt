package kyrielie.printer.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrinterConfigTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun testDefaultConfig() {
        val config = PrinterConfig()
        assertFalse(config.enabled)
        assertEquals(SpeedPreset.FAST_SMP, config.speedPreset)
        assertEquals(4.5, config.survivalReachLimit)
        assertEquals(2, config.blocksPerTick)
        assertEquals(PlacementMode.DECOUPLED_PACKET, config.placementMode)
        assertEquals(2, config.blockSwitchDelayTicks)
        assertEquals(10.0, config.maxAngleDelta)
        assertTrue(config.enableScaffolding)
        assertTrue(config.itemSaver)
        assertEquals(10, config.itemSaverThreshold)
    }

    @Test
    fun testApplyPresetStrictStealth() {
        val config = PrinterConfig()
        config.applyPreset(SpeedPreset.STRICT_STEALTH)

        assertEquals(SpeedPreset.STRICT_STEALTH, config.speedPreset)
        assertEquals(1, config.blocksPerTick)
        assertEquals(PlacementMode.AIM_ASSIST, config.placementMode)
        assertEquals(3, config.blockSwitchDelayTicks)
        assertEquals(8.0, config.maxAngleDelta)
    }

    @Test
    fun testApplyPresetFastSmp() {
        val config = PrinterConfig()
        config.applyPreset(SpeedPreset.FAST_SMP)

        assertEquals(SpeedPreset.FAST_SMP, config.speedPreset)
        assertEquals(2, config.blocksPerTick)
        assertEquals(PlacementMode.DECOUPLED_PACKET, config.placementMode)
        assertEquals(2, config.blockSwitchDelayTicks)
        assertEquals(10.0, config.maxAngleDelta)
    }

    @Test
    fun testApplyPresetMaxSpeed() {
        val config = PrinterConfig()
        config.applyPreset(SpeedPreset.MAX_SPEED)

        assertEquals(SpeedPreset.MAX_SPEED, config.speedPreset)
        assertEquals(6, config.blocksPerTick)
        assertEquals(PlacementMode.DECOUPLED_PACKET, config.placementMode)
        assertEquals(0, config.blockSwitchDelayTicks)
        assertEquals(15.0, config.maxAngleDelta)
    }

    @Test
    fun testSanitizeClamping() {
        val config = PrinterConfig(
            survivalReachLimit = 100.0,
            blocksPerTick = 50,
            maxAngleDelta = 999.0,
            blockSwitchDelayTicks = -5
        )
        config.sanitize()

        assertEquals(4.5, config.survivalReachLimit, "Reach limit should be capped at 4.5")
        assertEquals(8, config.blocksPerTick, "Blocks per tick should be capped at 8")
        assertEquals(15.0, config.maxAngleDelta, "Max angle delta should be capped at 15.0")
        assertEquals(0, config.blockSwitchDelayTicks, "Switch delay ticks should be non-negative")

        // Lower bounds
        config.survivalReachLimit = -1.0
        config.blocksPerTick = -10
        config.maxAngleDelta = 0.5
        config.blockSwitchDelayTicks = 20
        config.sanitize()

        assertEquals(1.0, config.survivalReachLimit, "Reach limit should be at least 1.0")
        assertEquals(1, config.blocksPerTick, "Blocks per tick should be at least 1")
        assertEquals(3.0, config.maxAngleDelta, "Max angle delta should be at least 3.0")
        assertEquals(10, config.blockSwitchDelayTicks, "Switch delay ticks should be capped at 10")
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = PrinterConfig(
            enabled = true,
            speedPreset = SpeedPreset.MAX_SPEED,
            survivalReachLimit = 4.2,
            blocksPerTick = 4,
            carpetProtocol = true,
            enableScaffolding = false,
            scaffoldPreferredFace = ScaffoldPreferredFace.TOP,
            scaffoldBlockItem = "minecraft:cobblestone",
            strictScaffoldWhitelist = false,
            placementMode = PlacementMode.AIM_ASSIST,
            triggerMode = KeyTriggerMode.HOLD_TO_PRINT,
            debugLogging = true
        )

        val json = gson.toJson(original)
        assertNotNull(json)
        assertTrue(json.contains("\"speedPreset\": \"MAX_SPEED\""))
        assertTrue(json.contains("\"placementMode\": \"AIM_ASSIST\""))

        val deserialized = gson.fromJson(json, PrinterConfig::class.java)
        assertEquals(original, deserialized)
    }
}
