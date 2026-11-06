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

data class PrinterConfig(
    var enabled: Boolean = false,
    var survivalReachLimit: Double = 4.5,
    var blocksPerTick: Int = 2,
    var carpetProtocol: Boolean = false,
    var enableScaffolding: Boolean = false,
    var scaffoldPreferredFace: ScaffoldPreferredFace = ScaffoldPreferredFace.UNDERSIDE,
    var scaffoldBlockItem: String = "minecraft:dirt",
    var removeScaffolding: Boolean = false,
    var enableMultiClick: Boolean = true,
    var itemSaver: Boolean = true,
    var itemSaverThreshold: Int = 10,
    var strictLayerSync: Boolean = false,
    var maxAngleDelta: Double = 10.0,
    var placementMode: PlacementMode = PlacementMode.DECOUPLED_PACKET,
    var triggerMode: KeyTriggerMode = KeyTriggerMode.TOGGLE,
    var debugLogging: Boolean = false
) {
    fun sanitize() {
        survivalReachLimit = survivalReachLimit.coerceIn(1.0, 4.5)
        blocksPerTick = blocksPerTick.coerceIn(1, 8)
        maxAngleDelta = maxAngleDelta.coerceIn(3.0, 15.0)
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
