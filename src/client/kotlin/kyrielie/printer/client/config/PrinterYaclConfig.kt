package kyrielie.printer.client.config

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

object PrinterYaclConfig {

    fun createScreen(parent: Screen?): Screen {
        val config = ConfigManager.config

        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("Litematica Printer Settings"))
            .category(
                ConfigCategory.createBuilder()
                    .name(Component.literal("General"))
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Enable Printer"))
                            .description(OptionDescription.of(Component.literal("Master switch for automatic placement.")))
                            .binding(false, { config.enabled }, { config.enabled = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Double>()
                            .name(Component.literal("Survival Reach Limit"))
                            .description(OptionDescription.of(Component.literal("Clamped maximum interaction distance from player eye.")))
                            .binding(4.5, { config.survivalReachLimit }, { config.survivalReachLimit = it })
                            .controller { opt -> DoubleSliderControllerBuilder.create(opt).range(1.0, 4.5).step(0.1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Blocks Per Tick"))
                            .description(OptionDescription.of(Component.literal("Placement budget per tick to avoid packet flood kicks.")))
                            .binding(2, { config.blocksPerTick }, { config.blocksPerTick = it })
                            .controller { opt -> IntegerSliderControllerBuilder.create(opt).range(1, 8).step(1) }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Carpet Protocol"))
                            .description(OptionDescription.of(Component.literal("Encode directional facing into hit vectors when on Carpet-compatible servers.")))
                            .binding(true, { config.carpetProtocol }, { config.carpetProtocol = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Enable Scaffolding"))
                            .description(OptionDescription.of(Component.literal("Automatically place temporary blocks to build floating structures.")))
                            .binding(true, { config.enableScaffolding }, { config.enableScaffolding = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Multi-Click Progression"))
                            .description(OptionDescription.of(Component.literal("Automatically tune repeaters, comparators, and noteblocks.")))
                            .binding(true, { config.enableMultiClick }, { config.enableMultiClick = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Show HUD Overlay"))
                            .description(OptionDescription.of(Component.literal("Display printer stats on screen.")))
                            .binding(true, { config.showHud }, { config.showHud = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .build()
            )
            .save { ConfigManager.save() }
            .build()
            .generateScreen(parent)
    }
}
