package kyrielie.printer.client.player

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Result of finding a visible face to place against.
 *
 * [placeAgainstPos]: The solid block in the real world to click on.
 * [clickedFace]: The face of the solid block that is clicked.
 * [hitVec]: The exact 3D contact vector on that face.
 */
data class VisiblePlacementFace(
    val placeAgainstPos: BlockPos,
    val clickedFace: Direction,
    val hitVec: Vec3
)

object FaceRaycaster {

    private val UV_OFFSETS = doubleArrayOf(0.5, 0.25, 0.75)

    /**
     * Determines which face of an existing neighbor block is unobstructed and visible
     * from the player's eye position to place a block at [targetPos].
     *
     * Strict Contact Rule:
     * Does NOT allow air-placement. An adjacent block in direction [face] must be
     * solid, clickable, and have a clear raycast line of sight.
     */
    fun findPlacementFace(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5
    ): VisiblePlacementFace? {
        // Evaluate all 6 potential supporting neighbors
        for (dir in Direction.entries) {
            val neighborPos = targetPos.relative(dir)
            val neighborState = world.getBlockState(neighborPos)

            // Must be solid enough to place against (not air, not liquid, not replaceable)
            if (neighborState.isAir || !neighborState.fluidState.isEmpty) continue
            if (neighborState.canBeReplaced()) continue

            // The face to click on the neighbor is the opposite of the direction from target to neighbor
            val clickedFace = dir.opposite

            // Check if clickedFace can physically face eyePos
            val cx = neighborPos.x + 0.5
            val cy = neighborPos.y + 0.5
            val cz = neighborPos.z + 0.5
            val faceNormal = Vec3(clickedFace.stepX.toDouble(), clickedFace.stepY.toDouble(), clickedFace.stepZ.toDouble())
            val deltaToEye = eyePos.subtract(cx, cy, cz)

            // Geometric backface culling
            if (faceNormal.dot(deltaToEye) <= 0.0) continue

            // Multi-point raycasting across face UV grid
            for (u in UV_OFFSETS) {
                for (v in UV_OFFSETS) {
                    val hitVec = when (clickedFace) {
                        Direction.UP    -> Vec3(neighborPos.x + u, neighborPos.y + 1.0, neighborPos.z + v)
                        Direction.DOWN  -> Vec3(neighborPos.x + u, neighborPos.y.toDouble(), neighborPos.z + v)
                        Direction.NORTH -> Vec3(neighborPos.x + u, neighborPos.y + v, neighborPos.z.toDouble())
                        Direction.SOUTH -> Vec3(neighborPos.x + u, neighborPos.y + v, neighborPos.z + 1.0)
                        Direction.WEST  -> Vec3(neighborPos.x.toDouble(), neighborPos.y + u, neighborPos.z + v)
                        Direction.EAST  -> Vec3(neighborPos.x + 1.0, neighborPos.y + u, neighborPos.z + v)
                    }

                    if (hitVec.distanceTo(eyePos) > maxReach) continue

                    // Cast ray against COLLIDER shape (accounts for stairs/slabs)
                    val clipCtx = ClipContext(
                        eyePos,
                        hitVec.add(faceNormal.scale(-0.05)), // Slight inward probe
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        player
                    )

                    val clipHit = world.clip(clipCtx)
                    if (clipHit.type == HitResult.Type.BLOCK && clipHit.blockPos == neighborPos) {
                        return VisiblePlacementFace(neighborPos, clickedFace, hitVec)
                    }
                }
            }
        }

        return null
    }
}
