package kyrielie.printer.client.guides

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.ComparatorMode
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class PlacementGuideStateTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun testRepeaterClickCalculation() {
        val repeater1 = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1)
        val repeater2 = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 2)
        val repeater3 = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 3)
        val repeater4 = Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 4)

        // 1 -> 3 requires 2 clicks
        assertEquals(2, PlacementGuideEngine.calculateClicksNeeded(repeater1, repeater3))
        // 1 -> 4 requires 3 clicks
        assertEquals(3, PlacementGuideEngine.calculateClicksNeeded(repeater1, repeater4))
        // 3 -> 1 wraps around: requires 2 clicks ((1 - 3 + 4) % 4 = 2)
        assertEquals(2, PlacementGuideEngine.calculateClicksNeeded(repeater3, repeater1))
        // 4 -> 1 requires 1 click
        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(repeater4, repeater1))
        // Same delay requires 0 clicks
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(repeater2, repeater2))
    }

    @Test
    fun testComparatorModeClickCalculation() {
        val compareMode = Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE)
        val subtractMode = Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT)

        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(compareMode, subtractMode))
        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(subtractMode, compareMode))
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(compareMode, compareMode))
    }

    @Test
    fun testNoteBlockPitchCalculation() {
        val note0 = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 0)
        val note5 = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 5)
        val note24 = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 24)
        val note2 = Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 2)

        assertEquals(5, PlacementGuideEngine.calculateClicksNeeded(note0, note5))
        // 24 -> 2 wraps: (2 - 24 + 25) % 25 = 3 clicks
        assertEquals(3, PlacementGuideEngine.calculateClicksNeeded(note24, note2))
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(note5, note5))
    }

    @Test
    fun testSnowLayersClickCalculation() {
        val layer1 = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 1)
        val layer4 = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 4)

        assertEquals(3, PlacementGuideEngine.calculateClicksNeeded(layer1, layer4))
        // Decreasing layers cannot be achieved by clicking, returns 0
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(layer4, layer1))
    }

    @Test
    fun testTrapdoorAndLeverToggleCalculation() {
        val closedDoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false)
        val openDoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)

        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(closedDoor, openDoor))
        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(openDoor, closedDoor))
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(openDoor, openDoor))

        val unpoweredLever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, false)
        val poweredLever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true)

        assertEquals(1, PlacementGuideEngine.calculateClicksNeeded(unpoweredLever, poweredLever))
        assertEquals(0, PlacementGuideEngine.calculateClicksNeeded(poweredLever, poweredLever))
    }

    @Test
    fun testMultiBlockSlavePartsFiltering() {
        // Bed head is secondary slave; bed foot is primary
        val bedHead = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
        val bedFoot = Blocks.BED.red().defaultBlockState().setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
        assertTrue(PlacementGuideEngine.isMultiBlockSlavePart(bedHead), "Bed head must be marked as slave part")
        assertFalse(PlacementGuideEngine.isMultiBlockSlavePart(bedFoot), "Bed foot is primary, not slave")

        // Door upper is secondary slave; door lower is primary
        val doorUpper = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
        val doorLower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
        assertTrue(PlacementGuideEngine.isMultiBlockSlavePart(doorUpper), "Door upper half must be marked as slave part")
        assertFalse(PlacementGuideEngine.isMultiBlockSlavePart(doorLower), "Door lower half is primary, not slave")

        // Normal solid block is not a slave part
        val stone = Blocks.STONE.defaultBlockState()
        assertFalse(PlacementGuideEngine.isMultiBlockSlavePart(stone))
    }

    @Test
    fun testNaturalDebrisIdentification() {
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.SHORT_GRASS.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.TALL_GRASS.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.FERN.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.LARGE_FERN.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.DEAD_BUSH.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.VINE.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.SNOW.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.DANDELION.defaultBlockState()))
        assertTrue(PlacementGuideEngine.isNaturalDebris(Blocks.POPPY.defaultBlockState()))

        assertFalse(PlacementGuideEngine.isNaturalDebris(Blocks.STONE.defaultBlockState()))
        assertFalse(PlacementGuideEngine.isNaturalDebris(Blocks.DIRT.defaultBlockState()))
        assertFalse(PlacementGuideEngine.isNaturalDebris(Blocks.OAK_PLANKS.defaultBlockState()))
    }
}
