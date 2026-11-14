package kyrielie.printer.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

enum class PlacementMode {
    DECOUPLED_PACKET,   // Approach C (Default): Syncs look via movement packet; screen untouched, movement unimpeded
    CROSSHAIR_SWEEP,    // Approach A: Passive mode; places only when crosshair sweeps over target faces
    AIM_ASSIST          // Approach B: Smoothly steers physical camera towards target block
}

enum class KeyTriggerMode {
    TOGGLE,             // Click keybind to toggle running state
    HOLD_TO_PRINT       // Active only while keybind is physically held down
}

enum class ScaffoldPreferredFace {
    UNDERSIDE,          // Downward backing for ceilings and thin floors
    INTERIOR_AUTO,      // Prioritizes hollow interior cavities
    TOP,                // Upward backing
    ANY                 // Any valid adjacent neighbor face
}

enum class SpeedPreset {
    STRICT_STEALTH,     // GrimAC/Vulcan safe: 1 block/tick, physical aim assist, 3-tick tool swap delay
    FAST_SMP,           // Default: 2 blocks/tick, decoupled movement packet aim, 2-tick switch break
    MAX_SPEED,          // Creative/Technical: up to 6 blocks/tick, 0-tick switch break
    CUSTOM              // User manual configuration
}

data class PrinterConfig(
    var enabled: Boolean = false,
    var speedPreset: SpeedPreset = SpeedPreset.FAST_SMP,
    var survivalReachLimit: Double = 4.5,
    var blocksPerTick: Int = 2,
    var carpetProtocol: Boolean = false,
    var enableScaffolding: Boolean = true,
    var scaffoldPreferredFace: ScaffoldPreferredFace = ScaffoldPreferredFace.UNDERSIDE,
    var scaffoldBlockItem: String = "minecraft:dirt",
    var scaffoldBlockWhitelist: String = "minecraft:dirt, minecraft:scaffolding",
    var strictScaffoldWhitelist: Boolean = true,
    var removeScaffolding: Boolean = false,
    var enableMultiClick: Boolean = true,
    var itemSaver: Boolean = true,
    var itemSaverThreshold: Int = 10,
    var strictLayerSync: Boolean = false,
    var maxAngleDelta: Double = 10.0,
    var blockSwitchDelayTicks: Int = 2,
    var placementMode: PlacementMode = PlacementMode.DECOUPLED_PACKET,
    var triggerMode: KeyTriggerMode = KeyTriggerMode.TOGGLE,
    var debugLogging: Boolean = false
) {
    fun applyPreset(preset: SpeedPreset) {
        speedPreset = preset
        when (preset) {
            SpeedPreset.STRICT_STEALTH -> {
                blocksPerTick = 1
                placementMode = PlacementMode.AIM_ASSIST
                blockSwitchDelayTicks = 3
                maxAngleDelta = 8.0
            }
            SpeedPreset.FAST_SMP -> {
                blocksPerTick = 2
                placementMode = PlacementMode.DECOUPLED_PACKET
                blockSwitchDelayTicks = 2
                maxAngleDelta = 10.0
            }
            SpeedPreset.MAX_SPEED -> {
                blocksPerTick = 6
                placementMode = PlacementMode.DECOUPLED_PACKET
                blockSwitchDelayTicks = 0
                maxAngleDelta = 15.0
            }
            SpeedPreset.CUSTOM -> {}
        }
    }
    fun sanitize() {
        survivalReachLimit = survivalReachLimit.coerceIn(1.0, 4.5)
        blocksPerTick = blocksPerTick.coerceIn(1, 8)
        maxAngleDelta = maxAngleDelta.coerceIn(3.0, 15.0)
        blockSwitchDelayTicks = blockSwitchDelayTicks.coerceIn(0, 10)
    }
}

object ConfigManager {

    private val logger = LoggerFactory.getLogger("Printer/ConfigManager")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance().configDir.resolve("litematica_printer.json").toFile()

    var config: PrinterConfig = PrinterConfig()
        private set

    fun load() {
        if (configFile.exists()) {
            try {
                configFile.reader().use {
                    config = gson.fromJson(it, PrinterConfig::class.java) ?: PrinterConfig()
                }
            } catch (e: Exception) {
                logger.error("Failed to parse config file, loading defaults", e)
                config = PrinterConfig()
            }
        } else {
            config = PrinterConfig()
            save()
        }
        config.sanitize()
    }

    fun save() {
        try {
            config.sanitize()
            configFile.writer().use {
                gson.toJson(config, it)
            }
        } catch (e: Exception) {
            logger.error("Failed to save config file", e)
        }
    }
}
