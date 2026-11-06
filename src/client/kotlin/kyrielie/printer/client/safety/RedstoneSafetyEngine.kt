package kyrielie.printer.client.safety

import kyrielie.printer.client.bridge.LitematicaBridge
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ObserverBlock
import net.minecraft.world.level.block.PistonBaseBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * Contextual Redstone Safety Engine (Phase 10).
 * Dynamic reactive safety gates modeled on Printer.java:
 * - Quasi-Connectivity (BUD) prevention
 * - Observer misfire avoidance
 * - Gravity block solid foundation enforcement
 */
object RedstoneSafetyEngine {

    /**
     * Evaluates whether [candidateState] at [pos] is safe to place given current world state.
     * Returns true if safe, false if placement should be delayed.
     */
    fun isSafeToPlace(
        world: Level,
        schematicWorld: Level,
        pos: BlockPos,
        candidateState: BlockState
    ): Boolean {
        // 1. Gravity Block Foundation Gate
        if (isGravityAffected(candidateState)) {
            val belowState = world.getBlockState(pos.below())
            if (belowState.isAir || belowState.canBeReplaced()) {
                return false // Delay until solid base is placed below
            }
        }

        // 2. Quasi-Connectivity (BUD) Safety Gate (isQCable)
        if (isRedstonePowerSource(candidateState)) {
            if (!isQCSafe(world, schematicWorld, pos)) {
                return false
            }
        }

        // 3. Observer Misfire Avoidance Gate (checkObserverOutputs)
        if (candidateState.block is ObserverBlock && candidateState.hasProperty(BlockStateProperties.FACING)) {
            val facing = candidateState.getValue(BlockStateProperties.FACING)
            val outputPos = pos.relative(facing.opposite)
            val schemOutput = schematicWorld.getBlockState(outputPos)
            val worldOutput = world.getBlockState(outputPos)

            // If schematic has receiving circuitry (piston, dropper, dispenser, wire) that is not yet placed
            if (!schemOutput.isAir && schemOutput != worldOutput && isPulseSensitive(schemOutput)) {
                return false // Delay observer to prevent 1-tick misfire pulse
            }
        }

        return true
    }

    private fun isGravityAffected(state: BlockState): Boolean {
        val b = state.block
        return b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.GRAVEL ||
               b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL ||
               b == Blocks.POINTED_DRIPSTONE || b == Blocks.DRAGON_EGG ||
               state.block.descriptionId.contains("concrete_powder")
    }

    private fun isRedstonePowerSource(state: BlockState): Boolean {
        val b = state.block
        return b == Blocks.REDSTONE_BLOCK || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH
    }

    /**
     * Checks if placing a power source at [pos] would QC-power an unbuilt schematic piston (BUD trap).
     */
    private fun isQCSafe(world: Level, schematicWorld: Level, pos: BlockPos): Boolean {
        // Pistons can be QC-powered diagonally or 2 blocks above
        val checkOffsets = arrayOf(
            pos.below().north(),
            pos.below().south(),
            pos.below().east(),
            pos.below().west(),
            pos.below(2)
        )

        for (pistonPos in checkOffsets) {
            val schemPiston = schematicWorld.getBlockState(pistonPos)
            if (schemPiston.block is PistonBaseBlock) {
                val worldPiston = world.getBlockState(pistonPos)
                if (worldPiston.block !is PistonBaseBlock) {
                    // Schematic requires a piston here that is not yet built; delay power source
                    return false
                }
            }
        }
        return true
    }

    private fun isPulseSensitive(state: BlockState): Boolean {
        val b = state.block
        return b is PistonBaseBlock || b == Blocks.DISPENSER || b == Blocks.DROPPER ||
               b == Blocks.TNT || b is RedStoneWireBlock
    }
}
