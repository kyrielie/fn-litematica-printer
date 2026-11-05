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

    // 5-point face sampling inspired by Baritone's aabbSideMultipliers
    private val UV_SAMPLES = arrayOf(
        Pair(0.5, 0.5),   // Center
        Pair(0.15, 0.5),  // Left-mid
        Pair(0.85, 0.5),  // Right-mid
        Pair(0.5, 0.15),  // Mid-bottom
        Pair(0.5, 0.85)   // Mid-top
    )

    /**
     * Searches for a valid, unobstructed neighbor block face to place [targetPos] against.
     * Returns [FaceRaycastResult] with explicit diagnostics explaining any rejection.
     */
    fun findPlacementFaceWithDiagnostics(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5
    ): FaceRaycastResult {
        val failureLog = ArrayList<String>()
        var foundAnySolidNeighbor = false

        for (dir in Direction.entries) {
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
                    Direction.WEST  -> Vec3(neighborPos.x.toDouble(), neighborPos.y + u, neighborPos.z + v)
                    Direction.EAST  -> Vec3(neighborPos.x + 1.0, neighborPos.y + u, neighborPos.z + v)
                }

                val dist = hitVec.distanceTo(eyePos)
                if (dist > maxReach) {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: dist=${"%.2f".format(dist)} > reach $maxReach")
                    continue
                }

                // Geometric facing check: allow glancing angles (dot >= -0.15)
                val dot = (eyePos.x - hitVec.x) * clickedFace.stepX +
                          (eyePos.y - hitVec.y) * clickedFace.stepY +
                          (eyePos.z - hitVec.z) * clickedFace.stepZ
                if (dot < -0.15) {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: backface dot=${"%.2f".format(dot)}")
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
                    if (clipHit.blockPos == neighborPos && clipHit.direction == clickedFace) {
                        return FaceRaycastResult.Success(VisiblePlacementFace(neighborPos, clickedFace, hitVec))
                    } else {
                        failureLog.add("Neighbor $clickedFace @ $neighborPos: occluded by ${clipHit.blockPos} (face=${clipHit.direction})")
                    }
                } else {
                    failureLog.add("Neighbor $clickedFace @ $neighborPos: ray missed block outline")
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
        maxReach: Double = 4.5
    ): VisiblePlacementFace? {
        val result = findPlacementFaceWithDiagnostics(world, player, eyePos, targetPos, maxReach)
        return (result as? FaceRaycastResult.Success)?.face
    }
}
