package kyrielie.printer.client

import com.mojang.blaze3d.platform.InputConstants
import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
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
        val logger = LoggerFactory.getLogger("Printer/Client")

        lateinit var toggleKey: KeyMapping
            private set
    }

    override fun onInitializeClient() {
        logger.info("[Printer] Initializing FN Litematica Printer Client...")
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

        // Register Client Tick
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (ConfigManager.config.triggerMode == kyrielie.printer.client.config.KeyTriggerMode.HOLD_TO_PRINT) {
                val isDown = toggleKey.isDown
                if (isDown != PrinterController.isRunning) {
                    PrinterController.setActive(isDown)
                    ConfigManager.config.enabled = isDown
                }
            } else {
                while (toggleKey.consumeClick()) {
                    PrinterController.toggle()
                    ConfigManager.config.enabled = PrinterController.isRunning
                    ConfigManager.save()

                    val status = if (PrinterController.isRunning) "ENABLED" else "DISABLED"
                    val bridgeStatus = if (LitematicaBridge.isAvailable()) "Linked" else "Standalone"

                    // Print comprehensive status directly to the console
                    logger.info("==================================================")
                    logger.info("[Printer] TOGGLE -> $status | Litematica Bridge: $bridgeStatus")
                    logger.info("[Printer] Config: mode=${ConfigManager.config.placementMode}, reach=${ConfigManager.config.survivalReachLimit}, budget=${ConfigManager.config.blocksPerTick}, maxAngle=${ConfigManager.config.maxAngleDelta}°, carpet=${ConfigManager.config.carpetProtocol}, scaffold=${ConfigManager.config.enableScaffolding}")
                    logger.info("==================================================")

                    // Brief in-game action bar confirmation
                    val chatColor = if (PrinterController.isRunning) "§a" else "§c"
                    mc.player?.sendOverlayMessage(
                        Component.literal("§6[Printer] $chatColor$status §7(Litematica: $bridgeStatus)")
                    )
                }
            }

            PrinterController.onClientTick(mc)
            ProtocolMovementGuard.onClientTickEnd()
        }

        logger.info("[Printer] Client initialized successfully.")
    }
}
