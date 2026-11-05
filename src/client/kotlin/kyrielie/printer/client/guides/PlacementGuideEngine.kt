package kyrielie.printer.client.guides

import kyrielie.printer.client.player.FaceRaycaster
import kyrielie.printer.client.player.FaceRaycastResult
import kyrielie.printer.client.player.InventoryRestockManager
import kyrielie.printer.client.player.PrinterController
import kyrielie.printer.client.player.VisiblePlacementFace
import kyrielie.printer.planner.CarpetProtocol
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

sealed class PlacementAction {
    data class PlaceBlock(
        val hitResult: BlockHitResult,
        val hand: InteractionHand,
        val targetPos: BlockPos,
        val aimVec: Vec3
    ) : PlacementAction()

    data class MultiClick(
        val hitResult: BlockHitResult,
        val hand: InteractionHand,
        val targetPos: BlockPos,
        val clickCount: Int,
        val aimVec: Vec3
    ) : PlacementAction()

    data class ScaffoldSequence(
        val site: ScaffoldSite,
        val scaffoldHand: InteractionHand,
        val targetHand: InteractionHand,
        val targetPos: BlockPos
    ) : PlacementAction()
}

object PlacementGuideEngine {

    /**
     * Inspects discrepancies between real world state and schematic state at [pos]
     * and constructs the appropriate [PlacementAction].
     */
    fun evaluate(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        eyePos: Vec3,
        pos: BlockPos,
        schematicState: BlockState,
        useCarpetProtocol: Boolean = true,
        enableScaffolding: Boolean = true,
        maxReach: Double = 4.5,
        debug: Boolean = false
    ): PlacementAction? {
        val currentState = world.getBlockState(pos)

        // 1. Check if the block is already placed but needs state progression
        if (currentState.block == schematicState.block) {
            val clicksNeeded = calculateClicksNeeded(currentState, schematicState)
            if (clicksNeeded > 0) {
                val centerVec = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                val hitResult = BlockHitResult(
                    centerVec,
                    Direction.UP,
                    pos,
                    false
                )
                return PlacementAction.MultiClick(hitResult, InteractionHand.MAIN_HAND, pos, clicksNeeded, centerVec)
            }
            return null
        }

        // 2. Must be replaceable or air in the real world
        if (!currentState.isAir && !currentState.canBeReplaced()) {
            if (debug) PrinterController.logger.debug("[Printer Debug] $pos rejected: world block ${currentState.block} is not air/replaceable")
            return null
        }

        // 3. Resolve required item to place this block
        val item = schematicState.block.asItem()
        val hand = InventoryRestockManager.prepareItem(mc, player, item)
        if (hand == null) {
            if (debug) PrinterController.logger.debug("[Printer Debug] $pos rejected: item $item not found in inventory!")
            return null
        }

        // 4. Try standard solid-neighbor face placement
        val raycastResult = FaceRaycaster.findPlacementFaceWithDiagnostics(world, player, eyePos, pos, maxReach)
        if (raycastResult is FaceRaycastResult.Success) {
            val placementFace = raycastResult.face
            var hitVec = placementFace.hitVec
            val realHitVec = placementFace.hitVec

            if (useCarpetProtocol && schematicState.hasProperty(BlockStateProperties.FACING)) {
                val facing = schematicState.getValue(BlockStateProperties.FACING)
                val facingId = CarpetProtocol.getFacingId(facing.name)
                val rawHitX = hitVec.x - placementFace.placeAgainstPos.x
                val encodedX = CarpetProtocol.applyCarpetProtocolV2(placementFace.placeAgainstPos.x, rawHitX, facingId)
                hitVec = Vec3(encodedX, hitVec.y, hitVec.z)
            } else if (useCarpetProtocol && schematicState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                val facing = schematicState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                val facingId = CarpetProtocol.getFacingId(facing.name)
                val rawHitX = hitVec.x - placementFace.placeAgainstPos.x
                val encodedX = CarpetProtocol.applyCarpetProtocolV2(placementFace.placeAgainstPos.x, rawHitX, facingId)
                hitVec = Vec3(encodedX, hitVec.y, hitVec.z)
            }

            val hitResult = BlockHitResult(
                hitVec,
                placementFace.clickedFace,
                placementFace.placeAgainstPos,
                false
            )
            return PlacementAction.PlaceBlock(hitResult, hand, pos, realHitVec)
        } else if (raycastResult is FaceRaycastResult.Failure) {
            if (debug) {
                val reasons = raycastResult.reasons.take(3).joinToString("; ")
                PrinterController.logger.debug("[Printer Debug] $pos rejected: $reasons")
            }
        }

        // 5. If no solid contact face exists, check for autonomous scaffolding
        if (enableScaffolding) {
            val site = ScaffoldHelper.findScaffoldSite(world, player, eyePos, pos, maxReach)
            if (site != null) {
                val scaffoldItem = net.minecraft.world.item.Items.DIRT
                val scaffoldHand = InventoryRestockManager.prepareItem(mc, player, scaffoldItem)
                if (scaffoldHand != null) {
                    return PlacementAction.ScaffoldSequence(site, scaffoldHand, hand, pos)
                }
            }
        }

        return null
    }

    private fun calculateClicksNeeded(current: BlockState, target: BlockState): Int {
        if (current.hasProperty(RepeaterBlock.DELAY) && target.hasProperty(RepeaterBlock.DELAY)) {
            val curDelay = current.getValue(RepeaterBlock.DELAY)
            val tarDelay = target.getValue(RepeaterBlock.DELAY)
            return (tarDelay - curDelay + 4) % 4
        }

        if (current.hasProperty(ComparatorBlock.MODE) && target.hasProperty(ComparatorBlock.MODE)) {
            val curMode = current.getValue(ComparatorBlock.MODE)
            val tarMode = target.getValue(ComparatorBlock.MODE)
            return if (curMode != tarMode) 1 else 0
        }

        if (current.hasProperty(NoteBlock.NOTE) && target.hasProperty(NoteBlock.NOTE)) {
            val curNote = current.getValue(NoteBlock.NOTE)
            val tarNote = target.getValue(NoteBlock.NOTE)
            return (tarNote - curNote + 25) % 25
        }

        return 0
    }
}
