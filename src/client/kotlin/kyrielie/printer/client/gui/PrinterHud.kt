package kyrielie.printer.client.gui

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.player.PrinterController
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object PrinterHud {

    private val logger = LoggerFactory.getLogger("Printer/PrinterHud")
    private val HUD_ID = Identifier.fromNamespaceAndPath("litematica_printer", "hud")

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.BOSS_BAR,
            HUD_ID,
            HudElement { graphics, _ -> render(graphics) }
        )
        logger.info("PrinterHud registered.")
    }

    private fun render(graphics: GuiGraphicsExtractor) {
        if (!ConfigManager.config.showHud) return
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null || mc.player == null) return

        val font = mc.font
        val running = PrinterController.isRunning && ConfigManager.config.enabled
        val statusText = if (running) "§aACTIVE" else "§cIDLE"

        val bridgeStatus = if (LitematicaBridge.isAvailable()) "§aLinked" else "§eStandalone"
        val speed = PrinterController.lastPlacementsPerSec
        val total = PrinterController.totalPlaced

        val x = 10
        var y = 10

        graphics.text(font, "§6[Litematica Printer] $statusText", x, y, 0xFFFFFFFF.toInt(), true)
        y += 11
        graphics.text(font, "§7Litematica: $bridgeStatus", x, y, 0xFFAAAAAA.toInt(), true)
        y += 11
        graphics.text(font, "§7Speed: §f$speed bps §7| Total: §f$total", x, y, 0xFFAAAAAA.toInt(), true)
        y += 11
        val mode = if (ConfigManager.config.carpetProtocol) "Carpet V2/V3" else "Vanilla Look"
        graphics.text(font, "§7Mode: §f$mode", x, y, 0xFFAAAAAA.toInt(), true)
    }
}
