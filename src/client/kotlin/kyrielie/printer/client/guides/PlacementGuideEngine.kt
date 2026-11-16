package kyrielie.printer.client.guides

import kyrielie.printer.client.bridge.LitematicaBridge
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

sealed class PlacementRejection {
    data class MissingItem(val item: net.minecraft.world.item.Item, val details: String? = null) : PlacementRejection()
    data object MultiBlockSlave : PlacementRejection()
    data class BlockOccupied(val existingBlock: net.minecraft.world.level.block.Block) : PlacementRejection()
    data class SafetyGateBlocked(val reason: String) : PlacementRejection()
    data class InfeasibleOrientation(val details: String) : PlacementRejection()
    data class RaycastFailed(val details: String, val faceAttempts: List<String> = emptyList()) : PlacementRejection()
    data class NoSolidSupport(val details: String) : PlacementRejection()
    data object AwaitingWaterlogging : PlacementRejection()
    data object AlreadyPlaced : PlacementRejection()
}

sealed class EvaluationResult {
    data class Success(val action: PlacementAction) : EvaluationResult()
    data class Rejected(val rejection: PlacementRejection) : EvaluationResult()
}

sealed class PlacementAction {
    data class PlaceBlock(
        val hitResult: BlockHitResult,
        val hand: InteractionHand,
        val targetPos: BlockPos,
        val aimVec: Vec3,
        val requiredItem: net.minecraft.world.item.Item,
        val forcedYaw: Float? = null,
        val forcedPitch: Float? = null
    ) : PlacementAction()

    data class MultiClick(
        val hitResult: BlockHitResult,
        val hand: InteractionHand,
        val targetPos: BlockPos,
        val clickCount: Int,
        val aimVec: Vec3
    ) : PlacementAction()

    data class TransformBlock(
        val hitResult: BlockHitResult,
        val hand: InteractionHand,
        val targetPos: BlockPos,
        val aimVec: Vec3,
        val toolPredicate: (net.minecraft.world.item.ItemStack) -> Boolean
    ) : PlacementAction()

    data class ClearObstacle(
        val targetPos: BlockPos,
        val aimVec: Vec3,
        val bestSlot: Int
    ) : PlacementAction()

    data class ScaffoldSequence(
        val site: ScaffoldSite,
        val scaffoldItem: net.minecraft.world.item.Item,
        val targetHand: InteractionHand,
        val targetPos: BlockPos
    ) : PlacementAction()
}

object PlacementGuideEngine {

    /**
     * Checks if a schematic state is the secondary slave component of a multi-block structure (G-7).
     * Upper doors, bed heads, and tall flower uppers are placed automatically with the base.
     */
    fun isMultiBlockSlavePart(state: BlockState): Boolean {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
                return true
            }
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            if (state.getValue(BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                return true
            }
        }
        return false
    }

    /**
     * Determines whether a block is soft natural debris suitable for surgical clearing (Phase 9).
     */
    fun isNaturalDebris(state: BlockState): Boolean {
        val b = state.block
        return b == net.minecraft.world.level.block.Blocks.SHORT_GRASS ||
               b == net.minecraft.world.level.block.Blocks.TALL_GRASS ||
               b == net.minecraft.world.level.block.Blocks.FERN ||
               b == net.minecraft.world.level.block.Blocks.LARGE_FERN ||
               b == net.minecraft.world.level.block.Blocks.DEAD_BUSH ||
               b == net.minecraft.world.level.block.Blocks.VINE ||
               b == net.minecraft.world.level.block.Blocks.SNOW ||
               b == net.minecraft.world.level.block.Blocks.HANGING_ROOTS ||
               b == net.minecraft.world.level.block.Blocks.DANDELION ||
               b == net.minecraft.world.level.block.Blocks.POPPY ||
               b.descriptionId.contains("flower")
    }

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
        useCarpetProtocol: Boolean = false,
        enableScaffolding: Boolean = false,
        maxReach: Double = 4.5,
        debug: Boolean = false,
        allowDecoupledBridge: Boolean = true
    ): PlacementAction? {
        val res = evaluateDetailed(
            mc, player, world, eyePos, pos, schematicState,
            useCarpetProtocol, enableScaffolding, maxReach, debug, allowDecoupledBridge
        )
        return (res as? EvaluationResult.Success)?.action
    }

    /**
     * Diagnostic evaluation: Inspects discrepancies and returns detailed [EvaluationResult]
     * capturing the precise reason why candidate blocks are rejected or deferred.
     */
    fun evaluateDetailed(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        eyePos: Vec3,
        pos: BlockPos,
        schematicState: BlockState,
        useCarpetProtocol: Boolean = false,
        enableScaffolding: Boolean = false,
        maxReach: Double = 4.5,
        debug: Boolean = false,
        allowDecoupledBridge: Boolean = true
    ): EvaluationResult {
        // G-7: Skip secondary multi-block components
        if (isMultiBlockSlavePart(schematicState)) return EvaluationResult.Rejected(PlacementRejection.MultiBlockSlave)

        val effectiveCarpet = if (useCarpetProtocol) {
            val supported = kyrielie.printer.client.bridge.CarpetProtocolDetector.isCarpetSupported()
            if (!supported) {
                kyrielie.printer.client.bridge.CarpetProtocolDetector.warnIfCarpetConfiguredButUnsupported()
            }
            supported
        } else false

        val currentState = world.getBlockState(pos)

        // 1. Check if the block is already placed but needs state progression or waterlogging
        if (currentState.block == schematicState.block) {
            val transform = kyrielie.printer.client.interaction.BlockTransformationRegistry.getRequiredTransformation(currentState, schematicState)
            if (transform is kyrielie.printer.client.interaction.TransformationAction.ItemInteraction &&
                transform.requiredItem == net.minecraft.world.item.Items.WATER_BUCKET
            ) {
                if (InventoryRestockManager.hasItem(player, net.minecraft.world.item.Items.WATER_BUCKET)) {
                    val centerVec = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    val hitResult = BlockHitResult(centerVec, Direction.UP, pos, false)
                    return EvaluationResult.Success(PlacementAction.TransformBlock(
                        hitResult,
                        InteractionHand.MAIN_HAND,
                        pos,
                        centerVec,
                        { !it.isEmpty && it.item == net.minecraft.world.item.Items.WATER_BUCKET }
                    ))
                } else {
                    return EvaluationResult.Rejected(PlacementRejection.MissingItem(net.minecraft.world.item.Items.WATER_BUCKET, "Water bucket needed for waterlogging"))
                }
            }

            val clicksNeeded = calculateClicksNeeded(currentState, schematicState)
            if (clicksNeeded > 0) {
                val centerVec = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                val hitResult = BlockHitResult(
                    centerVec,
                    Direction.UP,
                    pos,
                    false
                )
                return EvaluationResult.Success(PlacementAction.MultiClick(hitResult, InteractionHand.MAIN_HAND, pos, clicksNeeded, centerVec))
            }
            return EvaluationResult.Rejected(PlacementRejection.AlreadyPlaced)
        }

        // 2. Check for in-place block transformations (Phase 8: log stripping, tilling, waxing)
        if (!currentState.isAir && currentState.block != schematicState.block) {
            val transform = kyrielie.printer.client.interaction.BlockTransformationRegistry.getRequiredTransformation(currentState, schematicState)
            if (transform != null) {
                // Find visible/best exterior face of current block to interact with
                // Must have line of sight from eyePos without occlusion by intervening solid blocks
                val visibleFace = Direction.entries
                    .filter { dir ->
                        val adjacent = world.getBlockState(pos.relative(dir))
                        adjacent.isAir || adjacent.canBeReplaced() || !adjacent.isSolid
                    }
                    .mapNotNull { dir ->
                        val testVec = Vec3(
                            pos.x + 0.5 + dir.stepX * 0.49,
                            pos.y + 0.5 + dir.stepY * 0.49,
                            pos.z + 0.5 + dir.stepZ * 0.49
                        )
                        if (testVec.distanceTo(eyePos) > maxReach) return@mapNotNull null
                        val clip = world.clip(
                            net.minecraft.world.level.ClipContext(
                                eyePos,
                                testVec,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                player
                            )
                        )
                        val isClear = clip.type == net.minecraft.world.phys.HitResult.Type.MISS || clip.blockPos == pos
                        if (isClear) Pair(dir, testVec) else null
                    }
                    .maxByOrNull { (dir, testVec) ->
                        val faceVec = Vec3(dir.stepX.toDouble(), dir.stepY.toDouble(), dir.stepZ.toDouble())
                        val toEye = eyePos.subtract(testVec).normalize()
                        faceVec.dot(toEye)
                    }

                if (visibleFace == null) {
                    if (debug) PrinterController.logger.info("[Printer Debug] Transform at $pos rejected: all exterior faces occluded from player view")
                    return EvaluationResult.Rejected(PlacementRejection.RaycastFailed("All faces of $pos occluded from player view"))
                }

                val faceToClick = visibleFace.first
                val aimVec = visibleFace.second
                val hitResult = BlockHitResult(aimVec, faceToClick, pos, false)
                when (transform) {
                    is kyrielie.printer.client.interaction.TransformationAction.ToolInteraction -> {
                        if (InventoryRestockManager.hasItem(player, transform.toolPredicate)) {
                            return EvaluationResult.Success(PlacementAction.TransformBlock(
                                hitResult,
                                InteractionHand.MAIN_HAND,
                                pos,
                                aimVec,
                                transform.toolPredicate
                            ))
                        } else {
                            return EvaluationResult.Rejected(PlacementRejection.MissingItem(transform.representativeItem))
                        }
                    }
                    is kyrielie.printer.client.interaction.TransformationAction.ItemInteraction -> {
                        if (InventoryRestockManager.hasItem(player, transform.requiredItem)) {
                            return EvaluationResult.Success(PlacementAction.TransformBlock(
                                hitResult,
                                InteractionHand.MAIN_HAND,
                                pos,
                                aimVec,
                                { !it.isEmpty && it.item == transform.requiredItem }
                            ))
                        } else {
                            return EvaluationResult.Rejected(PlacementRejection.MissingItem(transform.requiredItem))
                        }
                    }
                }
            }

            // Phase 9: Surgical Obstacle Clearing for soft natural debris
            if (isNaturalDebris(currentState)) {
                val toolSet = kyrielie.printer.client.mining.ToolSet(
                    player,
                    kyrielie.printer.client.config.ConfigManager.config.itemSaver,
                    kyrielie.printer.client.config.ConfigManager.config.itemSaverThreshold
                )
                val bestSlot = toolSet.getBestSlot(currentState)
                val centerVec = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                return EvaluationResult.Success(PlacementAction.ClearObstacle(pos, centerVec, bestSlot))
            }
        }

        // Must be replaceable or air in the real world
        if (!currentState.isAir && !currentState.canBeReplaced()) {
            if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: world block ${currentState.block} is not air/replaceable")
            return EvaluationResult.Rejected(PlacementRejection.BlockOccupied(currentState.block))
        }

        // Redstone & Gravity Contextual Safety Gates (Phase 10)
        val schemWorld = LitematicaBridge.getSchematicWorld()
        if (schemWorld != null) {
            if (!kyrielie.printer.client.safety.RedstoneSafetyEngine.isSafeToPlace(world, schemWorld, pos, schematicState)) {
                if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: redstone/gravity safety gate blocked placement")
                return EvaluationResult.Rejected(PlacementRejection.SafetyGateBlocked("Redstone/gravity safety gate"))
            }
        }

        // 3. Resolve required item to place this block (G-3 pure non-mutating check)
        val strippedBlock = schematicState.block
        val rawVariant = kyrielie.printer.client.interaction.BlockTransformationRegistry.getUnstrippedVariant(strippedBlock)
        val directItem = strippedBlock.asItem()
        val item = if (rawVariant != null && !InventoryRestockManager.hasItem(player, directItem)) {
            val rawItem = rawVariant.asItem()
            val hasRaw = InventoryRestockManager.hasItem(player, rawItem)
            // Continue to place raw logs even if no axe is currently available to strip
            if (hasRaw) rawItem else directItem
        } else {
            directItem
        }
        if (!InventoryRestockManager.hasItem(player, item)) {
            if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: item $item not found in inventory!")
            return EvaluationResult.Rejected(PlacementRejection.MissingItem(item))
        }
        val hand = InventoryRestockManager.getPreferredHand(player, item)

        // 3b. Specialized water surface placement (Lily Pads & Frogspawn)
        if (item is net.minecraft.world.item.PlaceOnWaterBlockItem) {
            val belowPos = pos.below()
            val belowFluid = world.getFluidState(belowPos)
            if (belowFluid.isSource) {
                val waterHitVec = Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
                val hitResult = BlockHitResult(waterHitVec, Direction.UP, belowPos, false)
                return EvaluationResult.Success(PlacementAction.PlaceBlock(hitResult, hand, pos, waterHitVec, item))
            }
            return EvaluationResult.Rejected(PlacementRejection.NoSolidSupport("Water surface required for lily pad / frogspawn"))
        }

        // 4. Try standard solid-neighbor face placement
        val reqAxis = if (!effectiveCarpet && schematicState.hasProperty(BlockStateProperties.AXIS)) {
            schematicState.getValue(BlockStateProperties.AXIS)
        } else null

        val raycastResult = FaceRaycaster.findPlacementFaceWithDiagnostics(
            world,
            player,
            eyePos,
            pos,
            maxReach,
            allowDecoupledBridge,
            requiredAxis = reqAxis
        )
        if (raycastResult is FaceRaycastResult.Success) {
            val placementFace = raycastResult.face
            var hitVec = placementFace.hitVec
            val realHitVec = placementFace.hitVec

            // G-9: Non-Carpet pillar axis face constraint
            if (!effectiveCarpet && schematicState.hasProperty(BlockStateProperties.AXIS)) {
                val reqAxis = schematicState.getValue(BlockStateProperties.AXIS)
                if (placementFace.clickedFace.axis != reqAxis) {
                    if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: clicked face ${placementFace.clickedFace} axis does not match required pillar axis $reqAxis")
                    return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Clicked face ${placementFace.clickedFace} axis does not match required pillar axis $reqAxis"))
                }
            }

            // G-10: Slabs and stairs half boundary alignment
            if (!effectiveCarpet && schematicState.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                val slabType = schematicState.getValue(BlockStateProperties.SLAB_TYPE)
                if (slabType == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) {
                    if (placementFace.clickedFace == Direction.DOWN) return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Cannot place bottom slab against bottom face"))
                } else if (slabType == net.minecraft.world.level.block.state.properties.SlabType.TOP) {
                    if (placementFace.clickedFace == Direction.UP) return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Cannot place top slab against top face"))
                }
            }
            if (!effectiveCarpet && schematicState.hasProperty(BlockStateProperties.HALF)) {
                val half = schematicState.getValue(BlockStateProperties.HALF)
                if (half == net.minecraft.world.level.block.state.properties.Half.BOTTOM) {
                    if (placementFace.clickedFace == Direction.DOWN) return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Cannot place bottom-half block against bottom face"))
                } else if (half == net.minecraft.world.level.block.state.properties.Half.TOP) {
                    if (placementFace.clickedFace == Direction.UP) return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Cannot place top-half block against top face"))
                }
            }

            var forcedYaw: Float? = null
            var forcedPitch: Float? = null

            // Determine required look direction for directional blocks (stairs, repeaters, observers, pistons, trapdoors)
            val isStair = schematicState.block is net.minecraft.world.level.block.StairBlock
            val isObserver = schematicState.block is net.minecraft.world.level.block.ObserverBlock
            val isPiston = schematicState.block is net.minecraft.world.level.block.piston.PistonBaseBlock
            val isTrapdoor = schematicState.block is net.minecraft.world.level.block.TrapDoorBlock

            // Half-boundary alignment for stairs and slabs
            if (schematicState.hasProperty(BlockStateProperties.HALF) && !isTrapdoor) {
                val half = schematicState.getValue(BlockStateProperties.HALF)
                val targetYOffset = if (half == net.minecraft.world.level.block.state.properties.Half.TOP) 0.75 else 0.25
                if (placementFace.clickedFace.axis.isHorizontal) {
                    hitVec = Vec3(hitVec.x, placementFace.placeAgainstPos.y + targetYOffset, hitVec.z)
                }
            } else if (schematicState.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                val slabType = schematicState.getValue(BlockStateProperties.SLAB_TYPE)
                if (slabType == net.minecraft.world.level.block.state.properties.SlabType.TOP) {
                    if (placementFace.clickedFace.axis.isHorizontal) {
                        hitVec = Vec3(hitVec.x, placementFace.placeAgainstPos.y + 0.75, hitVec.z)
                    }
                } else if (slabType == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) {
                    if (placementFace.clickedFace.axis.isHorizontal) {
                        hitVec = Vec3(hitVec.x, placementFace.placeAgainstPos.y + 0.25, hitVec.z)
                    }
                }
            }

            if (effectiveCarpet) {
                if (schematicState.hasProperty(BlockStateProperties.FACING)) {
                    val facing = schematicState.getValue(BlockStateProperties.FACING)
                    val facingId = CarpetProtocol.getFacingId(facing.name)
                    val rawHitX = hitVec.x - placementFace.placeAgainstPos.x
                    val encodedX = CarpetProtocol.applyCarpetProtocolV2(placementFace.placeAgainstPos.x, rawHitX, facingId)
                    hitVec = Vec3(encodedX, hitVec.y, hitVec.z)
                } else if (schematicState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    val facing = schematicState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    val facingId = CarpetProtocol.getFacingId(facing.name)
                    val rawHitX = hitVec.x - placementFace.placeAgainstPos.x
                    val encodedX = CarpetProtocol.applyCarpetProtocolV2(placementFace.placeAgainstPos.x, rawHitX, facingId)
                    hitVec = Vec3(encodedX, hitVec.y, hitVec.z)
                }
            } else {
                // Vanilla Feasibility Solver (Non-Carpet Fallback - Gap G-8)
                // Evaluates whether natural line of sight to hitVec produces the required orientation.
                val naturalAimDelta = realHitVec.subtract(eyePos)
                val (naturalYaw, naturalPitch) = kyrielie.printer.util.deltaToYawPitch(naturalAimDelta.x, naturalAimDelta.y, naturalAimDelta.z)
                val naturalFacing = Direction.fromYRot(naturalYaw.toDouble())

                if (isTrapdoor) {
                    val reqFacing = schematicState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    if (placementFace.clickedFace.axis.isHorizontal) {
                        if (placementFace.clickedFace != reqFacing) {
                            if (debug) PrinterController.logger.info("[Printer Debug] $pos trapdoor rejected: clicked face ${placementFace.clickedFace} != required facing $reqFacing")
                            return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Trapdoor clicked face ${placementFace.clickedFace} != required facing $reqFacing"))
                        }
                    } else {
                        // When clicking UP or DOWN, vanilla sets FACING = player.getDirection().opposite
                        if (naturalFacing.opposite != reqFacing) {
                            if (debug) PrinterController.logger.info("[Printer Debug] $pos trapdoor rejected: natural facing ${naturalFacing.opposite} != required facing $reqFacing")
                            return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Trapdoor natural facing ${naturalFacing.opposite} != required facing $reqFacing"))
                        }
                    }
                } else if (schematicState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    val reqFacing = schematicState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    val expectedFacing = if (isObserver || isPiston) naturalFacing.opposite else naturalFacing
                    if (expectedFacing != reqFacing) {
                        if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: natural facing $expectedFacing != required facing $reqFacing (quadrant infeasible)")
                        return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Facing $expectedFacing != required facing $reqFacing"))
                    }
                } else if (schematicState.hasProperty(BlockStateProperties.FACING)) {
                    val reqFacing = schematicState.getValue(BlockStateProperties.FACING)
                    if (reqFacing.axis.isHorizontal) {
                        val expectedFacing = if (isObserver || isPiston) naturalFacing.opposite else naturalFacing
                        if (expectedFacing != reqFacing || naturalPitch < -45f || naturalPitch > 45f) {
                            if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: natural facing $expectedFacing != required horizontal facing $reqFacing (quadrant infeasible)")
                            return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Horizontal facing $expectedFacing != required $reqFacing"))
                        }
                    } else if (reqFacing == Direction.UP) {
                        // Observers and pistons face opposite of look: looking DOWN (pitch > 45°) places facing UP
                        val targetPitchValid = if (isObserver || isPiston) naturalPitch > 45f else naturalPitch < -45f
                        if (!targetPitchValid) {
                            if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: pitch ${"%.1f".format(naturalPitch)}° cannot place vertical facing UP")
                            return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Pitch ${"%.1f".format(naturalPitch)}° cannot place vertical facing UP"))
                        }
                    } else if (reqFacing == Direction.DOWN) {
                        // Observers and pistons face opposite of look: looking UP (pitch < -45°) places facing DOWN
                        val targetPitchValid = if (isObserver || isPiston) naturalPitch < -45f else naturalPitch > 45f
                        if (!targetPitchValid) {
                            if (debug) PrinterController.logger.info("[Printer Debug] $pos rejected: pitch ${"%.1f".format(naturalPitch)}° cannot place vertical facing DOWN")
                            return EvaluationResult.Rejected(PlacementRejection.InfeasibleOrientation("Pitch ${"%.1f".format(naturalPitch)}° cannot place vertical facing DOWN"))
                        }
                    }
                }
            }

            val hitResult = BlockHitResult(
                hitVec,
                placementFace.clickedFace,
                placementFace.placeAgainstPos,
                false
            )
            return EvaluationResult.Success(PlacementAction.PlaceBlock(hitResult, hand, pos, realHitVec, item, forcedYaw, forcedPitch))
        } else if (raycastResult is FaceRaycastResult.Failure) {
            if (debug) {
                val reasons = raycastResult.reasons.take(3).joinToString("; ")
                PrinterController.logger.info("[Printer Debug] $pos rejected: $reasons")
            }
        }

        // 5. If no solid contact face exists, check for autonomous scaffolding
        val isNoSolidNeighbor = raycastResult is FaceRaycastResult.Failure &&
            raycastResult.reasons.any { it.contains("No solid neighbor blocks exist") }

        if (enableScaffolding) {
            val scaffoldItem = ScaffoldHelper.resolveScaffoldItem(player)
            if (scaffoldItem != null) {
                val site = ScaffoldHelper.findScaffoldSite(
                    world,
                    player,
                    eyePos,
                    pos,
                    schematicState,
                    maxReach,
                    allowDecoupledBridge = allowDecoupledBridge
                )
                if (site != null) {
                    return EvaluationResult.Success(PlacementAction.ScaffoldSequence(site, scaffoldItem, hand, pos))
                } else {
                    return EvaluationResult.Rejected(PlacementRejection.NoSolidSupport("No valid scaffold site found (air-only constraint / pillar axis)"))
                }
            } else {
                return EvaluationResult.Rejected(PlacementRejection.NoSolidSupport("No valid scaffolding items (dirt/scaffolding) in inventory matching whitelist"))
            }
        } else if (isNoSolidNeighbor) {
            return EvaluationResult.Rejected(PlacementRejection.NoSolidSupport("No solid neighbor blocks exist adjacent to target (scaffolding disabled)"))
        }

        val failureReasons = (raycastResult as? FaceRaycastResult.Failure)?.reasons ?: emptyList()
        val failDetail = failureReasons.firstOrNull() ?: "Raycast occluded or out of reach"
        return EvaluationResult.Rejected(PlacementRejection.RaycastFailed(failDetail, failureReasons))
    }

    fun calculateClicksNeeded(current: BlockState, target: BlockState): Int {
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

        // Multi-click snow layer stacking (references/PlacementGuide.java line 330)
        if (current.hasProperty(BlockStateProperties.LAYERS) && target.hasProperty(BlockStateProperties.LAYERS)) {
            val curLayers = current.getValue(BlockStateProperties.LAYERS)
            val tarLayers = target.getValue(BlockStateProperties.LAYERS)
            if (tarLayers > curLayers) {
                return tarLayers - curLayers
            }
        }

        // Trapdoor and fence gate toggles (references/PlacementGuide.java line 370)
        if (current.hasProperty(BlockStateProperties.OPEN) && target.hasProperty(BlockStateProperties.OPEN)) {
            val curOpen = current.getValue(BlockStateProperties.OPEN)
            val tarOpen = target.getValue(BlockStateProperties.OPEN)
            if (curOpen != tarOpen) return 1
        }

        // Lever state toggles (references/PlacementGuide.java line 345)
        if (current.hasProperty(BlockStateProperties.POWERED) && target.hasProperty(BlockStateProperties.POWERED)) {
            val curPowered = current.getValue(BlockStateProperties.POWERED)
            val tarPowered = target.getValue(BlockStateProperties.POWERED)
            if (curPowered != tarPowered) return 1
        }

        return 0
    }
}
