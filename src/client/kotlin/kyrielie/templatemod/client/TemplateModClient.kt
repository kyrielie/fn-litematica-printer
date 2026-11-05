package kyrielie.templatemod.client

import com.mojang.blaze3d.platform.InputConstants
import kyrielie.templatemod.TemplateMod
import kyrielie.templatemod.client.config.ConfigManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object TemplateModClient : ClientModInitializer {
    val logger = LoggerFactory.getLogger("TemplateMod/Client")

    private lateinit var exampleKey: KeyMapping

    override fun onInitializeClient() {
        logger.info("TemplateMod client initializing...")
        ConfigManager.load()

        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TemplateMod.MOD_ID, "general"))
        exampleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.templatemod.example_action", InputConstants.KEY_K, category)
        )

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (exampleKey.consumeClick()) {
                mc.player?.sendSystemMessage(Component.literal("§b[TemplateMod] §fAction triggered! Toggle=${ConfigManager.config.exampleToggle}"))
                logger.info("Example key action consumed.")
            }
        }

        logger.info("TemplateMod client initialized.")
    }
}
