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

    // 8-point face sampling: standard 5 interior points + 3 upper edge samples for forward bridging
    private val UV_SAMPLES = arrayOf(
        Pair(0.5, 0.5),   // Center
        Pair(0.15, 0.5),  // Left-mid
        Pair(0.85, 0.5),  // Right-mid
        Pair(0.5, 0.15),  // Mid-bottom
        Pair(0.5, 0.85),  // Mid-top
        Pair(0.5, 0.92),  // Upper perimeter edge (bridging)
        Pair(0.2, 0.92),  // Upper-left edge
        Pair(0.8, 0.92)   // Upper-right edge
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
        maxReach: Double = 4.5
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

                // Geometric facing check: allow glancing angles (dot >= -0.15, or -0.45 when looking down at upper horizontal edge)
                val dot = (eyePos.x - hitVec.x) * clickedFace.stepX +
                          (eyePos.y - hitVec.y) * clickedFace.stepY +
                          (eyePos.z - hitVec.z) * clickedFace.stepZ
                val minDot = if (clickedFace.axis.isHorizontal && eyePos.y > hitVec.y && v >= 0.85) -0.45 else -0.15
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
                    if (clipHit.blockPos == neighborPos && (clipHit.direction == clickedFace || (clickedFace.axis.isHorizontal && clipHit.direction == Direction.UP && v >= 0.85))) {
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
