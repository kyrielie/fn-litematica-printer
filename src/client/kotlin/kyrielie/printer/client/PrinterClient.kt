package kyrielie.printer.client

import com.mojang.blaze3d.platform.InputConstants
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.gui.PrinterHud
import kyrielie.printer.client.player.PrinterController
import kyrielie.printer.client.player.ProtocolMovementGuard
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

class PrinterClient : ClientModInitializer {

    companion object {
        private val logger = LoggerFactory.getLogger("Printer/Client")

        lateinit var toggleKey: KeyMapping
            private set
    }

    override fun onInitializeClient() {
        logger.info("Initializing Litematica Printer Client...")
        ConfigManager.load()

        // Register keybind (V by default)
        val printerCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("litematica_printer", "general"))
        toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.litematica_printer.toggle",
                InputConstants.KEY_V,
                printerCategory
            )
        )

        // Register HUD element
        PrinterHud.register()

        // Register Client Tick
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (toggleKey.consumeClick()) {
                ConfigManager.config.enabled = !ConfigManager.config.enabled
                ConfigManager.save()
                PrinterController.toggle()
                val status = if (PrinterController.isRunning) "§aENABLED" else "§cDISABLED"
                mc.player?.sendSystemMessage(
                    Component.literal("§6[Printer] $status")
                )
            }

            PrinterController.onClientTick(mc)
            ProtocolMovementGuard.onClientTickEnd()
        }

        logger.info("Litematica Printer Client initialized.")
    }
}
