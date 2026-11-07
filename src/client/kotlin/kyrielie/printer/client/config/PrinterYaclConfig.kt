package kyrielie.printer.client.config

import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder
import dev.isxander.yacl3.api.controller.EnumDropdownControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.StringControllerBuilder
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
                        Option.createBuilder<KeyTriggerMode>()
                            .name(Component.literal("Key Trigger Mode"))
                            .description(
                                OptionDescription.of(
                                    Component.literal("Toggle: Click once to activate, click again to stop.\nHold to Print: Active strictly while keybind is physically held.")
                                )
                            )
                            .binding(KeyTriggerMode.TOGGLE, { config.triggerMode }, { config.triggerMode = it })
                            .controller { opt ->
                                EnumDropdownControllerBuilder.create(opt).formatValue { mode ->
                                    when (mode) {
                                        KeyTriggerMode.TOGGLE -> Component.literal("Toggle")
                                        KeyTriggerMode.HOLD_TO_PRINT -> Component.literal("Hold to Print")
                                    }
                                }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<PlacementMode>()
                            .name(Component.literal("Placement Look Mode"))
                            .description(
                                OptionDescription.of(
                                    Component.literal(
                                        "Decoupled Packet (Approach C - Recommended): Aligns look via outgoing movement packet. Player view never snaps, movement unimpeded, server sees exact block aim.\n" +
                                        "Crosshair Sweep (Approach A): Passive mode. Only places when crosshair naturally sweeps across block faces.\n" +
                                        "Aim Assist (Approach B): Smoothly guides player physical camera towards the block."
                                    )
                                )
                            )
                            .binding(PlacementMode.DECOUPLED_PACKET, { config.placementMode }, { config.placementMode = it })
                            .controller { opt ->
                                EnumDropdownControllerBuilder.create(opt).formatValue { mode ->
                                    when (mode) {
                                        PlacementMode.DECOUPLED_PACKET -> Component.literal("Decoupled Packet (Recommended)")
                                        PlacementMode.CROSSHAIR_SWEEP -> Component.literal("Crosshair Sweep (Passive)")
                                        PlacementMode.AIM_ASSIST -> Component.literal("Aim Assist (Camera Guide)")
                                    }
                                }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Double>()
                            .name(Component.literal("Survival Reach Limit"))
                            .description(OptionDescription.of(Component.literal("Clamped maximum interaction distance from player eye.")))
                            .binding(4.5, { config.survivalReachLimit }, { config.survivalReachLimit = it })
                            .controller { opt ->
                                DoubleSliderControllerBuilder.create(opt)
                                    .range(1.0, 4.5)
                                    .step(0.1)
                                    .formatValue { Component.literal(String.format(java.util.Locale.US, "%.1f blocks", it)) }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Blocks Per Tick"))
                            .description(OptionDescription.of(Component.literal("Placement budget per tick to avoid packet flood kicks.")))
                            .binding(2, { config.blocksPerTick }, { config.blocksPerTick = it })
                            .controller { opt ->
                                IntegerSliderControllerBuilder.create(opt)
                                    .range(1, 8)
                                    .step(1)
                                    .formatValue { Component.literal("$it blocks/tick") }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Strict Layer Sync"))
                            .description(
                                OptionDescription.of(
                                    Component.literal("When enabled, fully completes the lowest Y-layer before placing any block on higher layers.")
                                )
                            )
                            .binding(false, { config.strictLayerSync }, { config.strictLayerSync = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Carpet Protocol"))
                            .description(OptionDescription.of(Component.literal("Encode directional facing into hit vectors when on Carpet-compatible servers.")))
                            .binding(false, { config.carpetProtocol }, { config.carpetProtocol = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Item Saver Protection"))
                            .description(OptionDescription.of(Component.literal("Prevents tools from breaking by automatically stopping use when durability is low.")))
                            .binding(true, { config.itemSaver }, { config.itemSaver = it })
                            .controller(BooleanControllerBuilder::create)
                            .build()
                    )
                    .option(
                        Option.createBuilder<Int>()
                            .name(Component.literal("Item Saver Threshold"))
                            .description(OptionDescription.of(Component.literal("Minimum remaining durability before a tool is protected from breaking.")))
                            .binding(10, { config.itemSaverThreshold }, { config.itemSaverThreshold = it })
                            .controller { opt ->
                                IntegerSliderControllerBuilder.create(opt)
                                    .range(1, 50)
                                    .step(1)
                                    .formatValue { Component.literal("$it durability") }
                            }
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
                        Option.createBuilder<ScaffoldPreferredFace>()
                            .name(Component.literal("Scaffold Preferred Face"))
                            .description(OptionDescription.of(Component.literal("Preferred direction to attach temporary scaffolding.")))
                            .binding(ScaffoldPreferredFace.UNDERSIDE, { config.scaffoldPreferredFace }, { config.scaffoldPreferredFace = it })
                            .controller { opt ->
                                EnumDropdownControllerBuilder.create(opt).formatValue { face ->
                                    when (face) {
                                        ScaffoldPreferredFace.UNDERSIDE -> Component.literal("Underside (Floor/Ceiling)")
                                        ScaffoldPreferredFace.INTERIOR_AUTO -> Component.literal("Interior (Cavity Auto)")
                                        ScaffoldPreferredFace.TOP -> Component.literal("Top (Above Overhang)")
                                        ScaffoldPreferredFace.ANY -> Component.literal("Any Neighbor")
                                    }
                                }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<String>()
                            .name(Component.literal("Scaffold Block Item"))
                            .description(OptionDescription.of(Component.literal("Item identifier used for temporary scaffolding.")))
                            .binding("minecraft:dirt", { config.scaffoldBlockItem }, { config.scaffoldBlockItem = it })
                            .controller { opt ->
                                DropdownStringControllerBuilder.create(opt)
                                    .values("minecraft:dirt", "minecraft:cobblestone", "minecraft:netherrack", "minecraft:scaffolding")
                                    .allowAnyValue(true)
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Remove Scaffolding"))
                            .description(OptionDescription.of(Component.literal("Automatically break temporary scaffolding blocks once the schematic target block is confirmed placed.")))
                            .binding(false, { config.removeScaffolding }, { config.removeScaffolding = it })
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
                        Option.createBuilder<Double>()
                            .name(Component.literal("Max Look Angle Limit"))
                            .description(OptionDescription.of(Component.literal("Maximum angular offset (degrees) from crosshair in Sweep mode (3°-15°).")))
                            .binding(10.0, { config.maxAngleDelta }, { config.maxAngleDelta = it })
                            .controller { opt ->
                                DoubleSliderControllerBuilder.create(opt)
                                    .range(3.0, 15.0)
                                    .step(0.5)
                                    .formatValue { Component.literal(String.format(java.util.Locale.US, "%.1f°", it)) }
                            }
                            .build()
                    )
                    .option(
                        Option.createBuilder<Boolean>()
                            .name(Component.literal("Debug Logging"))
                            .description(OptionDescription.of(Component.literal("Enable verbose packet-level telemetry (ACKs, confirmations, candidate rejections). Default false for clean console.")))
                            .binding(false, { config.debugLogging }, { config.debugLogging = it })
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
