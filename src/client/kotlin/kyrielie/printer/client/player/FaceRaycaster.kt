package kyrielie.printer.client.player

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

data class VisiblePlacementFace(
    val placeAgainstPos: BlockPos,
    val clickedFace: Direction,
    val hitVec: Vec3
)

sealed class FaceRaycastResult {
    data class Success(val face: VisiblePlacementFace) : FaceRaycastResult()
    data class Failure(val reasons: List<String>) : FaceRaycastResult()
}

object FaceRaycaster {

    // 10-point face sampling: standard interior points + edge/rim samples for forward bridging
    private val UV_SAMPLES = arrayOf(
        Pair(0.5, 0.5),   // Center
        Pair(0.15, 0.5),  // Left-mid
        Pair(0.85, 0.5),  // Right-mid
        Pair(0.5, 0.15),  // Mid-bottom
        Pair(0.5, 0.85),  // Mid-top
        Pair(0.5, 0.92),  // Upper perimeter edge (bridging)
        Pair(0.2, 0.92),  // Upper-left edge
        Pair(0.8, 0.92),  // Upper-right edge
        Pair(0.5, 0.97),  // Rim edge (steep forward bridging)
        Pair(0.2, 0.97)   // Rim edge corner
    )

    /**
     * Determines whether right-clicking a block triggers an in-world interaction / container GUI (F-05).
     */
    fun isInteractiveBlock(state: net.minecraft.world.level.block.state.BlockState): Boolean {
        val block = state.block
        return block is net.minecraft.world.level.block.BaseEntityBlock ||
               block is net.minecraft.world.level.block.CraftingTableBlock ||
               block is net.minecraft.world.level.block.DoorBlock ||
               block is net.minecraft.world.level.block.TrapDoorBlock ||
               block is net.minecraft.world.level.block.FenceGateBlock ||
               block is net.minecraft.world.level.block.ButtonBlock ||
               block is net.minecraft.world.level.block.LeverBlock ||
               block is net.minecraft.world.level.block.DiodeBlock ||
               block is net.minecraft.world.level.block.NoteBlock ||
               block is net.minecraft.world.level.block.JukeboxBlock ||
               block is net.minecraft.world.level.block.AnvilBlock ||
               block is net.minecraft.world.level.block.GrindstoneBlock ||
               block is net.minecraft.world.level.block.LoomBlock ||
               block is net.minecraft.world.level.block.CartographyTableBlock ||
               block is net.minecraft.world.level.block.SmithingTableBlock ||
               block is net.minecraft.world.level.block.StonecutterBlock ||
               block is net.minecraft.world.level.block.LecternBlock ||
               block is net.minecraft.world.level.block.BellBlock ||
               block is net.minecraft.world.level.block.CrafterBlock ||
               block is net.minecraft.world.level.block.RespawnAnchorBlock ||
               block is net.minecraft.world.level.block.DaylightDetectorBlock
    }

    /**
     * Searches for a valid, unobstructed neighbor block face to place [targetPos] against.
     * Returns [FaceRaycastResult] with explicit diagnostics explaining any rejection.
     */
    fun findPlacementFaceWithDiagnostics(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5,
        allowDecoupledBridge: Boolean = true
    ): FaceRaycastResult {
        val failureLog = ArrayList<String>()
        var foundAnySolidNeighbor = false

        // F-05: Prioritize non-interactive solid blocks first to prevent accidental container opening
        val sortedDirs = Direction.entries.sortedBy { dir ->
            val neighborState = world.getBlockState(targetPos.relative(dir))
            if (isInteractiveBlock(neighborState)) 1 else 0
        }

        for (dir in sortedDirs) {
            val neighborPos = targetPos.relative(dir)
            val neighborState = world.getBlockState(neighborPos)

            // Must not be air, fluid, or replaceable
            if (neighborState.isAir || !neighborState.fluidState.isEmpty || neighborState.canBeReplaced()) {
                continue
            }

            foundAnySolidNeighbor = true
            val clickedFace = dir.opposite

            for ((u, v) in UV_SAMPLES) {
                val hitVec = when (clickedFace) {
                    Direction.UP    -> Vec3(neighborPos.x + u, neighborPos.y + 1.0, neighborPos.z + v)
                    Direction.DOWN  -> Vec3(neighborPos.x + u, neighborPos.y.toDouble(), neighborPos.z + v)
                    Direction.NORTH -> Vec3(neighborPos.x + u, neighborPos.y + v, neighborPos.z.toDouble())
                    Direction.SOUTH -> Vec3(neighborPos.x + u, neighborPos.y + v, neighborPos.z + 1.0)
                    Direction.WEST  -> Vec3(neighborPos.x.toDouble(), neighborPos.y + v, neighborPos.z + u)
                    Direction.EAST  -> Vec3(neighborPos.x + 1.0, neighborPos.y + v, neighborPos.z + u)
                }

                val dist = hitVec.distanceTo(eyePos)
                if (dist > maxReach) {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: dist=${"%.2f".format(dist)} > reach $maxReach")
                    continue
                }

                // Geometric facing check: allow glancing angles and forward-down bridging angles
                val dot = (eyePos.x - hitVec.x) * clickedFace.stepX +
                          (eyePos.y - hitVec.y) * clickedFace.stepY +
                          (eyePos.z - hitVec.z) * clickedFace.stepZ
                val isForwardBridging = clickedFace.axis.isHorizontal && eyePos.y >= hitVec.y && v >= 0.85
                val minDot = if (isForwardBridging) {
                    if (allowDecoupledBridge) -1.05 else -0.75
                } else {
                    -0.15
                }
                if (dot < minDot) {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: backface dot=${"%.2f".format(dot)} < min $minDot")
                    continue
                }

                // Inward penetration step into neighbor block body
                val inwardStep = Vec3(
                    clickedFace.stepX.toDouble(),
                    clickedFace.stepY.toDouble(),
                    clickedFace.stepZ.toDouble()
                ).scale(-0.05)
                val rayTarget = hitVec.add(inwardStep)

                val clipCtx = ClipContext(
                    eyePos,
                    rayTarget,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
                )

                val clipHit = world.clip(clipCtx)
                if (clipHit.type == HitResult.Type.BLOCK) {
                    val hitFace = clipHit.direction
                    val isAnchor = clipHit.blockPos == neighborPos
                    val isEdgeBridge = isAnchor && (hitFace == clickedFace || (isForwardBridging && hitFace == Direction.UP))
                    if (isEdgeBridge) {
                        return FaceRaycastResult.Success(VisiblePlacementFace(neighborPos, clickedFace, hitVec))
                    } else {
                        failureLog.add("Neighbor $clickedFace @ $neighborPos: occluded by ${clipHit.blockPos} (face=$hitFace)")
                    }
                } else {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: ray missed block outline")
                }
            }

            // Option 2 (Decoupled Geometric Contact Fallback):
            // When in DECOUPLED_PACKET mode, if line-of-sight from camera to outer face is occluded
            // only by the anchor block itself (looking forward over the edge), allow placement against the anchor face
            // provided the hit point is within reach and not obstructed by other obstacles.
            if (allowDecoupledBridge && clickedFace.axis.isHorizontal && eyePos.y >= neighborPos.y) {
                val centerHitVec = Vec3(
                    neighborPos.x + 0.5 + clickedFace.stepX * 0.5,
                    neighborPos.y + 0.95,
                    neighborPos.z + 0.5 + clickedFace.stepZ * 0.5
                )
                if (centerHitVec.distanceTo(eyePos) <= maxReach) {
                    val checkRay = world.clip(
                        ClipContext(
                            eyePos,
                            centerHitVec,
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player
                        )
                    )
                    if (checkRay.type == HitResult.Type.MISS || checkRay.blockPos == neighborPos) {
                        return FaceRaycastResult.Success(VisiblePlacementFace(neighborPos, clickedFace, centerHitVec))
                    }
                }
            }
        }

        if (!foundAnySolidNeighbor) {
            failureLog.add("No solid neighbor blocks exist adjacent to $targetPos")
        }

        return FaceRaycastResult.Failure(failureLog)
    }

    /**
     * Standard helper returning nullable [VisiblePlacementFace].
     */
    fun findPlacementFace(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5,
        allowDecoupledBridge: Boolean = true
    ): VisiblePlacementFace? {
        val result = findPlacementFaceWithDiagnostics(world, player, eyePos, targetPos, maxReach, allowDecoupledBridge)
        return (result as? FaceRaycastResult.Success)?.face
    }
}
