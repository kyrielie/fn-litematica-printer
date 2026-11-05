package kyrielie.printer

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

class PrinterMod : ModInitializer {
    companion object {
        const val MOD_ID = "litematica_printer"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
    }

    override fun onInitialize() {
        LOGGER.info("Litematica Printer core initialized.")
    }
}
