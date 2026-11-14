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
import net.minecraft.world.level.block.state.properties.BlockStateProperties

sealed class TransformationAction {
    data class ToolInteraction(
        val representativeItem: Item,
        val toolPredicate: (ItemStack) -> Boolean
    ) : TransformationAction()
    data class ItemInteraction(val requiredItem: Item, val stackAmount: Int = 1) : TransformationAction()
}

/**
 * Registry for smart in-place block transformations (Phase 8).
 * Handles log stripping, copper waxing/scraping, soil tilling, waterlogging, and campfire extinguishing.
 */
object BlockTransformationRegistry {

    val AXES: Set<Item> = setOf(
        Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
        Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE
    )

    val SHOVELS: Set<Item> = setOf(
        Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL,
        Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
    )

    val HOES: Set<Item> = setOf(
        Items.NETHERITE_HOE, Items.DIAMOND_HOE, Items.IRON_HOE,
        Items.GOLDEN_HOE, Items.STONE_HOE, Items.WOODEN_HOE
    )

    /**
     * Complete vanilla 26.3 log-to-stripped mapping (25 pairs including poplar and pale oak).
     */
    val RAW_TO_STRIPPED: Map<Block, Block> = mapOf(
        Blocks.OAK_LOG to Blocks.STRIPPED_OAK_LOG,
        Blocks.OAK_WOOD to Blocks.STRIPPED_OAK_WOOD,
        Blocks.SPRUCE_LOG to Blocks.STRIPPED_SPRUCE_LOG,
        Blocks.SPRUCE_WOOD to Blocks.STRIPPED_SPRUCE_WOOD,
        Blocks.BIRCH_LOG to Blocks.STRIPPED_BIRCH_LOG,
        Blocks.BIRCH_WOOD to Blocks.STRIPPED_BIRCH_WOOD,
        Blocks.JUNGLE_LOG to Blocks.STRIPPED_JUNGLE_LOG,
        Blocks.JUNGLE_WOOD to Blocks.STRIPPED_JUNGLE_WOOD,
        Blocks.ACACIA_LOG to Blocks.STRIPPED_ACACIA_LOG,
        Blocks.ACACIA_WOOD to Blocks.STRIPPED_ACACIA_WOOD,
        Blocks.DARK_OAK_LOG to Blocks.STRIPPED_DARK_OAK_LOG,
        Blocks.DARK_OAK_WOOD to Blocks.STRIPPED_DARK_OAK_WOOD,
        Blocks.MANGROVE_LOG to Blocks.STRIPPED_MANGROVE_LOG,
        Blocks.MANGROVE_WOOD to Blocks.STRIPPED_MANGROVE_WOOD,
        Blocks.CHERRY_LOG to Blocks.STRIPPED_CHERRY_LOG,
        Blocks.CHERRY_WOOD to Blocks.STRIPPED_CHERRY_WOOD,
        Blocks.PALE_OAK_LOG to Blocks.STRIPPED_PALE_OAK_LOG,
        Blocks.PALE_OAK_WOOD to Blocks.STRIPPED_PALE_OAK_WOOD,
        Blocks.POPLAR_LOG to Blocks.STRIPPED_POPLAR_LOG,
        Blocks.POPLAR_WOOD to Blocks.STRIPPED_POPLAR_WOOD,
        Blocks.CRIMSON_STEM to Blocks.STRIPPED_CRIMSON_STEM,
        Blocks.CRIMSON_HYPHAE to Blocks.STRIPPED_CRIMSON_HYPHAE,
        Blocks.WARPED_STEM to Blocks.STRIPPED_WARPED_STEM,
        Blocks.WARPED_HYPHAE to Blocks.STRIPPED_WARPED_HYPHAE,
        Blocks.BAMBOO_BLOCK to Blocks.STRIPPED_BAMBOO_BLOCK
    )

    val STRIPPED_TO_RAW: Map<Block, Block> = RAW_TO_STRIPPED.entries.associate { (k, v) -> v to k }

    fun getUnstrippedVariant(strippedBlock: Block): Block? = STRIPPED_TO_RAW[strippedBlock]

    fun getStrippedVariant(rawBlock: Block): Block? = RAW_TO_STRIPPED[rawBlock]

    fun getRequiredTransformation(current: BlockState, target: BlockState): TransformationAction? {
        val currentBlock = current.block
        val targetBlock = target.block

        // 1. Waterlogging Pipeline
        if (target.hasProperty(BlockStateProperties.WATERLOGGED) && target.getValue(BlockStateProperties.WATERLOGGED)) {
            if (currentBlock == targetBlock && current.hasProperty(BlockStateProperties.WATERLOGGED) && !current.getValue(BlockStateProperties.WATERLOGGED)) {
                return TransformationAction.ItemInteraction(Items.WATER_BUCKET, 1)
            }
        }

        // 2. Soil Preparation (Farmland / Dirt Path)
        if (targetBlock == Blocks.FARMLAND) {
            if (currentBlock == Blocks.DIRT || currentBlock == Blocks.GRASS_BLOCK || currentBlock == Blocks.DIRT_PATH) {
                return TransformationAction.ToolInteraction(Items.DIAMOND_HOE) { !it.isEmpty && it.item in HOES }
            }
        }
        if (targetBlock == Blocks.DIRT_PATH) {
            if (currentBlock == Blocks.DIRT || currentBlock == Blocks.GRASS_BLOCK || currentBlock == Blocks.COARSE_DIRT ||
                currentBlock == Blocks.ROOTED_DIRT || currentBlock == Blocks.PODZOL || currentBlock == Blocks.MYCELIUM) {
                return TransformationAction.ToolInteraction(Items.DIAMOND_SHOVEL) { !it.isEmpty && it.item in SHOVELS }
            }
        }

        // 3. Log Stripping (Unstripped log/wood -> Stripped log/wood)
        val stripped = getStrippedVariant(currentBlock)
        if (stripped != null && stripped == targetBlock) {
            return TransformationAction.ToolInteraction(Items.DIAMOND_AXE) { !it.isEmpty && it.item in AXES }
        }

        // 4. Campfire extinguishing
        if (currentBlock is CampfireBlock && targetBlock is CampfireBlock) {
            if (current.getValue(CampfireBlock.LIT) && !target.getValue(CampfireBlock.LIT)) {
                return TransformationAction.ToolInteraction(Items.DIAMOND_SHOVEL) { !it.isEmpty && it.item in SHOVELS }
            }
        }

        // 5. Candle stacking
        if (currentBlock is CandleBlock && targetBlock is CandleBlock) {
            val curCandles = current.getValue(CandleBlock.CANDLES)
            val tarCandles = target.getValue(CandleBlock.CANDLES)
            if (tarCandles > curCandles) {
                return TransformationAction.ItemInteraction(targetBlock.asItem(), tarCandles - curCandles)
            }
        }

        // 6. Sea pickle stacking
        if (currentBlock is SeaPickleBlock && targetBlock is SeaPickleBlock) {
            val curPickles = current.getValue(SeaPickleBlock.PICKLES)
            val tarPickles = target.getValue(SeaPickleBlock.PICKLES)
            if (tarPickles > curPickles) {
                return TransformationAction.ItemInteraction(Items.SEA_PICKLE, tarPickles - curPickles)
            }
        }

        // 7. Copper Waxing (Unwaxed copper -> Waxed copper)
        val targetId = BuiltInRegistries.BLOCK.getKey(targetBlock).path
        val currentId = BuiltInRegistries.BLOCK.getKey(currentBlock).path
        if (targetId.startsWith("waxed_") && targetId.removePrefix("waxed_") == currentId) {
            return TransformationAction.ItemInteraction(Items.HONEYCOMB, 1)
        }

        // 8. Copper Scraping (Wax scraping and Patina de-oxidation with Axe)
        if (isCopperScrapeNeeded(currentId, targetId)) {
            return TransformationAction.ToolInteraction(Items.DIAMOND_AXE) { !it.isEmpty && it.item in AXES }
        }

        return null
    }

    private fun isCopperScrapeNeeded(currentId: String, targetId: String): Boolean {
        if (!currentId.contains("copper") || !targetId.contains("copper")) return false

        val currentWaxed = currentId.startsWith("waxed_")
        val targetWaxed = targetId.startsWith("waxed_")

        // Case A: Scrape wax from waxed copper
        if (currentWaxed && !targetWaxed) {
            val currentBase = currentId.removePrefix("waxed_")
            val curOxidation = getCopperOxidationLevel(currentBase)
            val tarOxidation = getCopperOxidationLevel(targetId)
            if (getCopperBaseType(currentBase) == getCopperBaseType(targetId) && curOxidation >= tarOxidation) {
                return true
            }
        }

        // Case B: Scrape oxidation patina
        val currentBase = currentId.removePrefix("waxed_")
        val targetBase = targetId.removePrefix("waxed_")
        val curOxidation = getCopperOxidationLevel(currentBase)
        val tarOxidation = getCopperOxidationLevel(targetBase)
        return curOxidation > tarOxidation && getCopperBaseType(currentBase) == getCopperBaseType(targetBase)
    }

    private fun getCopperOxidationLevel(name: String): Int = when {
        name.startsWith("oxidized_") -> 3
        name.startsWith("weathered_") -> 2
        name.startsWith("exposed_") -> 1
        else -> 0
    }

    private fun getCopperBaseType(name: String): String {
        return name.removePrefix("oxidized_")
            .removePrefix("weathered_")
            .removePrefix("exposed_")
    }
}
