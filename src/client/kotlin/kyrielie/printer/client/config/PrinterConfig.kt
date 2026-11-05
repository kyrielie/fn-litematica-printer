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

data class PrinterConfig(
    var enabled: Boolean = false,
    var survivalReachLimit: Double = 4.5,
    var blocksPerTick: Int = 2,
    var carpetProtocol: Boolean = true,
    var enableScaffolding: Boolean = true,
    var enableMultiClick: Boolean = true,
    var maxAngleDelta: Double = 10.0,
    var placementMode: PlacementMode = PlacementMode.DECOUPLED_PACKET,
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
