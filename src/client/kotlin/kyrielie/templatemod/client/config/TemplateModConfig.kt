package kyrielie.templatemod.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

data class TemplateModConfig(
    var exampleToggle: Boolean = true,
    var scanRadius: Int = 10,
    var debugLogging: Boolean = false
) {
    fun sanitize() {
        if (scanRadius <= 0) scanRadius = 10
    }
}

object ConfigManager {
    private val logger = LoggerFactory.getLogger("TemplateMod/Config")
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TemplateModConfig::class.java, InstanceCreator { TemplateModConfig() })
        .setPrettyPrinting()
        .create()

    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("templatemod/config.json").toFile()
    }

    var config: TemplateModConfig = TemplateModConfig()
        private set

    fun load() {
        configFile.parentFile?.mkdirs()
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            config = gson.fromJson(configFile.readText(), TemplateModConfig::class.java) ?: TemplateModConfig()
            config.sanitize()
        } catch (e: Exception) {
            logger.warn("Failed to parse config, resetting to defaults: ${e.message}", e)
            config = TemplateModConfig()
            save()
        }
    }

    fun save() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(gson.toJson(config))
        } catch (e: Exception) {
            logger.error("Failed to save config: ${e.message}", e)
        }
    }
}
