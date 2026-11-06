package kyrielie.printer.client.interaction

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.SeaPickleBlock
import net.minecraft.world.level.block.state.BlockState

sealed class TransformationAction {
    data class ToolInteraction(val toolPredicate: (ItemStack) -> Boolean) : TransformationAction()
    data class ItemInteraction(val requiredItem: Item, val stackAmount: Int = 1) : TransformationAction()
}

/**
 * Registry for smart in-place block transformations (Phase 8).
 * Handles log stripping, copper waxing/scraping, soil tilling, and campfire extinguishing.
 */
object BlockTransformationRegistry {

    private val AXES = setOf(
        Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE
    )

    private val SHOVELS = setOf(
        Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
        Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
    )

    private val HOES = setOf(
        Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.IRON_HOE,
        Items.GOLDEN_HOE, Items.STONE_HOE, Items.WOODEN_HOE
    )

    fun getRequiredTransformation(current: BlockState, target: BlockState): TransformationAction? {
        val currentBlock = current.block
        val targetBlock = target.block

        // 1. Soil Preparation (Farmland / Dirt Path)
        if (targetBlock == Blocks.FARMLAND) {
            if (currentBlock == Blocks.DIRT || currentBlock == Blocks.GRASS_BLOCK || currentBlock == Blocks.DIRT_PATH) {
                return TransformationAction.ToolInteraction { !it.isEmpty && it.item in HOES }
            }
        }
        if (targetBlock == Blocks.DIRT_PATH) {
            if (currentBlock == Blocks.DIRT || currentBlock == Blocks.GRASS_BLOCK || currentBlock == Blocks.COARSE_DIRT || currentBlock == Blocks.ROOTED_DIRT || currentBlock == Blocks.PODZOL || currentBlock == Blocks.MYCELIUM) {
                return TransformationAction.ToolInteraction { !it.isEmpty && it.item in SHOVELS }
            }
        }

        // 2. Log Stripping (Unstripped log/wood -> Stripped log/wood)
        val targetId = BuiltInRegistries.BLOCK.getKey(targetBlock).path
        val currentId = BuiltInRegistries.BLOCK.getKey(currentBlock).path
        if (targetId.startsWith("stripped_") && targetId.removePrefix("stripped_") == currentId) {
            return TransformationAction.ToolInteraction { !it.isEmpty && it.item in AXES }
        }

        // 3. Campfire extinguishing
        if (currentBlock is CampfireBlock && targetBlock is CampfireBlock) {
            if (current.getValue(CampfireBlock.LIT) && !target.getValue(CampfireBlock.LIT)) {
                return TransformationAction.ToolInteraction { !it.isEmpty && it.item in SHOVELS }
            }
        }

        // 4. Candle stacking
        if (currentBlock is CandleBlock && targetBlock is CandleBlock) {
            val curCandles = current.getValue(CandleBlock.CANDLES)
            val tarCandles = target.getValue(CandleBlock.CANDLES)
            if (tarCandles > curCandles) {
                return TransformationAction.ItemInteraction(targetBlock.asItem(), tarCandles - curCandles)
            }
        }

        // 5. Sea pickle stacking
        if (currentBlock is SeaPickleBlock && targetBlock is SeaPickleBlock) {
            val curPickles = current.getValue(SeaPickleBlock.PICKLES)
            val tarPickles = target.getValue(SeaPickleBlock.PICKLES)
            if (tarPickles > curPickles) {
                return TransformationAction.ItemInteraction(Items.SEA_PICKLE, tarPickles - curPickles)
            }
        }

        // 6. Copper Waxing (Unwaxed copper -> Waxed copper)
        if (targetId.startsWith("waxed_") && targetId.removePrefix("waxed_") == currentId) {
            return TransformationAction.ItemInteraction(Items.HONEYCOMB, 1)
        }

        return null
    }
}
