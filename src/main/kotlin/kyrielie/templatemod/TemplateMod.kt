package kyrielie.templatemod

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object TemplateMod : ModInitializer {
    const val MOD_ID = "templatemod"
    val logger = LoggerFactory.getLogger("TemplateMod")

    override fun onInitialize() {
        logger.info("TemplateMod common initialized for Minecraft 26.3.")
    }
}
