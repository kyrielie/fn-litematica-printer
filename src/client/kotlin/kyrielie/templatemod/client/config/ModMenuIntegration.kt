package kyrielie.templatemod.client.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent: Screen ->
            createConfigScreen(parent)
        }
    }

    private fun createConfigScreen(parent: Screen): Screen {
        val cfg = ConfigManager.config
        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("Template Mod Configuration"))
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("General"))
                    .tooltip(Component.literal("General settings for Template Mod"))
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Enable Feature"))
                            .description(OptionDescription.of(Component.literal("Toggle main feature state.")))
                            .binding(true, { cfg.exampleToggle }, { cfg.exampleToggle = it })
                            .controller { opt -> BooleanControllerBuilder.create(opt).yesNoFormatter().coloured(true) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Scan Radius"))
                            .description(OptionDescription.of(Component.literal("Block search radius.")))
                            .binding(10, { cfg.scanRadius }, { cfg.scanRadius = it })
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 32).step(1) }
                            .build()
                    )
                    .build()
            )
            .save { ConfigManager.save() }
            .build()
            .generateScreen(parent)
    }
}
