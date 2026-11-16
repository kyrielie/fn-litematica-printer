package kyrielie.printer.client.interaction

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.SeaPickleBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class BlockTransformationTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            net.minecraft.core.registries.BuiltInRegistries.ITEM.forEach { item ->
                val holder = item.builtInRegistryHolder()
                if (!holder.areComponentsBound()) {
                    holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY)
                }
            }
        }
    }

    @Test
    fun testLogStrippingRegistryIntegrity() {
        assertEquals(25, BlockTransformationRegistry.RAW_TO_STRIPPED.size, "Must have exactly 25 vanilla log/wood pairs")
        assertEquals(25, BlockTransformationRegistry.STRIPPED_TO_RAW.size, "Inverse map must also have 25 entries")

        // Check specific types including newer 26.x woods
        assertEquals(Blocks.STRIPPED_OAK_LOG, BlockTransformationRegistry.getStrippedVariant(Blocks.OAK_LOG))
        assertEquals(Blocks.OAK_LOG, BlockTransformationRegistry.getUnstrippedVariant(Blocks.STRIPPED_OAK_LOG))

        assertEquals(Blocks.STRIPPED_PALE_OAK_LOG, BlockTransformationRegistry.getStrippedVariant(Blocks.PALE_OAK_LOG))
        assertEquals(Blocks.PALE_OAK_LOG, BlockTransformationRegistry.getUnstrippedVariant(Blocks.STRIPPED_PALE_OAK_LOG))

        assertEquals(Blocks.STRIPPED_POPLAR_LOG, BlockTransformationRegistry.getStrippedVariant(Blocks.POPLAR_LOG))
        assertEquals(Blocks.POPLAR_LOG, BlockTransformationRegistry.getUnstrippedVariant(Blocks.STRIPPED_POPLAR_LOG))

        assertEquals(Blocks.STRIPPED_BAMBOO_BLOCK, BlockTransformationRegistry.getStrippedVariant(Blocks.BAMBOO_BLOCK))
        assertEquals(Blocks.BAMBOO_BLOCK, BlockTransformationRegistry.getUnstrippedVariant(Blocks.STRIPPED_BAMBOO_BLOCK))

        assertEquals(Blocks.STRIPPED_CRIMSON_STEM, BlockTransformationRegistry.getStrippedVariant(Blocks.CRIMSON_STEM))
        assertEquals(Blocks.CRIMSON_STEM, BlockTransformationRegistry.getUnstrippedVariant(Blocks.STRIPPED_CRIMSON_STEM))
    }

    @Test
    fun testToolSetsContainAllTiers() {
        // Axes
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.NETHERITE_AXE))
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.DIAMOND_AXE))
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.IRON_AXE))
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.GOLDEN_AXE))
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.STONE_AXE))
        assertTrue(BlockTransformationRegistry.AXES.contains(Items.WOODEN_AXE))

        // Shovels
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.NETHERITE_SHOVEL))
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.DIAMOND_SHOVEL))
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.IRON_SHOVEL))
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.GOLDEN_SHOVEL))
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.STONE_SHOVEL))
        assertTrue(BlockTransformationRegistry.SHOVELS.contains(Items.WOODEN_SHOVEL))

        // Hoes
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.NETHERITE_HOE))
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.DIAMOND_HOE))
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.IRON_HOE))
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.GOLDEN_HOE))
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.STONE_HOE))
        assertTrue(BlockTransformationRegistry.HOES.contains(Items.WOODEN_HOE))
    }

    @Test
    fun testLogStrippingTransformation() {
        val current = Blocks.OAK_LOG.defaultBlockState()
        val target = Blocks.STRIPPED_OAK_LOG.defaultBlockState()

        val action = BlockTransformationRegistry.getRequiredTransformation(current, target)
        assertNotNull(action)
        assertTrue(action is TransformationAction.ToolInteraction)
        val toolAction = action as TransformationAction.ToolInteraction
        assertEquals(Items.DIAMOND_AXE, toolAction.representativeItem)
        assertTrue(toolAction.toolPredicate(Items.IRON_AXE.defaultInstance))
        assertFalse(toolAction.toolPredicate(Items.IRON_PICKAXE.defaultInstance))
    }

    @Test
    fun testSoilTillingTransformation() {
        // Dirt -> Farmland requires Hoe
        val dirt = Blocks.DIRT.defaultBlockState()
        val farmland = Blocks.FARMLAND.defaultBlockState()

        val action = BlockTransformationRegistry.getRequiredTransformation(dirt, farmland)
        assertNotNull(action)
        assertTrue(action is TransformationAction.ToolInteraction)
        val toolAction = action as TransformationAction.ToolInteraction
        assertEquals(Items.DIAMOND_HOE, toolAction.representativeItem)

        // Dirt -> Dirt Path requires Shovel
        val dirtPath = Blocks.DIRT_PATH.defaultBlockState()
        val pathAction = BlockTransformationRegistry.getRequiredTransformation(dirt, dirtPath)
        assertNotNull(pathAction)
        assertTrue(pathAction is TransformationAction.ToolInteraction)
        val pathTool = pathAction as TransformationAction.ToolInteraction
        assertEquals(Items.DIAMOND_SHOVEL, pathTool.representativeItem)
    }

    @Test
    fun testWaterloggingTransformation() {
        val dryStairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false)
        val waterloggedStairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)

        val action = BlockTransformationRegistry.getRequiredTransformation(dryStairs, waterloggedStairs)
        assertNotNull(action)
        assertTrue(action is TransformationAction.ItemInteraction)
        val itemAction = action as TransformationAction.ItemInteraction
        assertEquals(Items.WATER_BUCKET, itemAction.requiredItem)
        assertEquals(1, itemAction.stackAmount)
    }

    @Test
    fun testCandleAndSeaPickleStacking() {
        // Candle stacking: 1 to 3
        val oneCandle = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1)
        val threeCandles = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3)

        val candleAction = BlockTransformationRegistry.getRequiredTransformation(oneCandle, threeCandles)
        assertNotNull(candleAction)
        assertTrue(candleAction is TransformationAction.ItemInteraction)
        val candleItem = candleAction as TransformationAction.ItemInteraction
        assertEquals(Items.CANDLE, candleItem.requiredItem)
        assertEquals(2, candleItem.stackAmount)

        // Sea pickle stacking: 1 to 4
        val onePickle = Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 1)
        val fourPickles = Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 4)

        val pickleAction = BlockTransformationRegistry.getRequiredTransformation(onePickle, fourPickles)
        assertNotNull(pickleAction)
        assertTrue(pickleAction is TransformationAction.ItemInteraction)
        val pickleItem = pickleAction as TransformationAction.ItemInteraction
        assertEquals(Items.SEA_PICKLE, pickleItem.requiredItem)
        assertEquals(3, pickleItem.stackAmount)
    }

    @Test
    fun testCampfireExtinguishing() {
        val litCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)
        val unlitCampfire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false)

        val action = BlockTransformationRegistry.getRequiredTransformation(litCampfire, unlitCampfire)
        assertNotNull(action)
        assertTrue(action is TransformationAction.ToolInteraction)
        val toolAction = action as TransformationAction.ToolInteraction
        assertEquals(Items.DIAMOND_SHOVEL, toolAction.representativeItem)
    }

    @Test
    fun testCopperWaxingAndScraping() {
        val copperBlock = Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState()
        val waxedCopperBlock = Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState()

        // Unwaxed -> Waxed requires Honeycomb
        val waxingAction = BlockTransformationRegistry.getRequiredTransformation(copperBlock, waxedCopperBlock)
        assertNotNull(waxingAction)
        assertTrue(waxingAction is TransformationAction.ItemInteraction)
        assertEquals(Items.HONEYCOMB, (waxingAction as TransformationAction.ItemInteraction).requiredItem)

        // Waxed -> Unwaxed requires Axe (scraping wax)
        val unwaxingAction = BlockTransformationRegistry.getRequiredTransformation(waxedCopperBlock, copperBlock)
        assertNotNull(unwaxingAction)
        assertTrue(unwaxingAction is TransformationAction.ToolInteraction)
        assertEquals(Items.DIAMOND_AXE, (unwaxingAction as TransformationAction.ToolInteraction).representativeItem)

        // Weathered -> Exposed requires Axe (scraping patina)
        val weathered = Blocks.COPPER_BLOCK.weathering().weathered().defaultBlockState()
        val exposed = Blocks.COPPER_BLOCK.weathering().exposed().defaultBlockState()
        val deoxidizeAction = BlockTransformationRegistry.getRequiredTransformation(weathered, exposed)
        assertNotNull(deoxidizeAction)
        assertTrue(deoxidizeAction is TransformationAction.ToolInteraction)
        assertEquals(Items.DIAMOND_AXE, (deoxidizeAction as TransformationAction.ToolInteraction).representativeItem)
    }
}
