package kyrielie.printer.client.bridge

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object CarpetProtocolDetector {

    private val logger = LoggerFactory.getLogger("Printer/CarpetDetector")
    private var warnedUnsupported = false

    private val CARPET_ACCURATE = Identifier.fromNamespaceAndPath("carpet", "accurate_block_placement")
    private val CARPET_EXTRA_ACCURATE = Identifier.fromNamespaceAndPath("carpet_extra", "accurate_block_placement")
    private val CARPET_HELLO = Identifier.fromNamespaceAndPath("carpet", "hello")

    /**
     * Checks if the connected server has registered Carpet / Carpet Extra communication channels.
     * Returns false if the server is a standard Paper, Fabric, or Vanilla server without Carpet Extra.
     */
    fun isCarpetSupported(): Boolean {
        return try {
            ClientPlayNetworking.canSend(CARPET_ACCURATE) ||
            ClientPlayNetworking.canSend(CARPET_EXTRA_ACCURATE) ||
            ClientPlayNetworking.canSend(CARPET_HELLO)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Emits a warning when Carpet protocol is toggled on in mod settings but the server lacks Carpet support.
     */
    fun warnIfCarpetConfiguredButUnsupported() {
        if (!warnedUnsupported) {
            warnedUnsupported = true
            logger.warn(
                "[Printer] Carpet Protocol is enabled in config, but the server does not support the Carpet accurateBlockPlacement protocol. " +
                "Falling back to native decoupled packet placement to prevent server placement rejections."
            )
        }
    }

    fun reset() {
        warnedUnsupported = false
    }
}
