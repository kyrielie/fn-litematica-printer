package kyrielie.printer.client.guides

import kyrielie.printer.client.player.FaceRaycaster
import kyrielie.printer.client.player.VisiblePlacementFace
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Result of scaffold site evaluation.
 *
 * [scaffoldPos]: The temporary position where scaffold (e.g. Dirt) will be placed.
 * [anchorFace]: The visible face of an existing world block to place the scaffold against.
 * [targetFaceFromScaffold]: The face on the scaffold to click on when placing the target block.
 */
data class ScaffoldSite(
    val scaffoldPos: BlockPos,
    val anchorFace: VisiblePlacementFace,
    val targetFaceFromScaffold: VisiblePlacementFace
)

object ScaffoldHelper {

    /**
     * Searches adjacent cardinal neighbors of [targetPos] for a location where a temporary
     * block can be legally placed against existing solid geometry.
     */
    fun findScaffoldSite(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5
    ): ScaffoldSite? {
        // Probe all 6 directions adjacent to target
        for (dir in net.minecraft.core.Direction.entries) {
            val candidateScaffoldPos = targetPos.relative(dir)

            // Scaffold position must be empty/air in the real world
            val currentScaffoldState = world.getBlockState(candidateScaffoldPos)
            if (!currentScaffoldState.isAir && !currentScaffoldState.canBeReplaced()) continue

            // Must NOT overlap the player's own position
            if (candidateScaffoldPos.distToCenterSqr(player.x, player.y, player.z) < 1.0) continue

            // Phase 1 check: Can the scaffold block be placed against existing solid geometry (NOT targetPos)?
            val anchorFace = FaceRaycaster.findPlacementFace(world, player, eyePos, candidateScaffoldPos, maxReach)
                ?: continue

            if (anchorFace.placeAgainstPos == targetPos) continue // Cannot place scaffold against nonexistent target

            // Phase 2 check: If the scaffold is placed, does it provide a face toward targetPos?
            val faceToTarget = dir.opposite
            val hitVecOnScaffold = when (faceToTarget) {
                net.minecraft.core.Direction.UP    -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 1.0, candidateScaffoldPos.z + 0.5)
                net.minecraft.core.Direction.DOWN  -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y.toDouble(), candidateScaffoldPos.z + 0.5)
                net.minecraft.core.Direction.NORTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z.toDouble())
                net.minecraft.core.Direction.SOUTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 1.0)
                net.minecraft.core.Direction.WEST  -> Vec3(candidateScaffoldPos.x.toDouble(), candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
                net.minecraft.core.Direction.EAST  -> Vec3(candidateScaffoldPos.x + 1.0, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
            }

            if (hitVecOnScaffold.distanceTo(eyePos) > maxReach) continue

            val targetPlacement = VisiblePlacementFace(candidateScaffoldPos, faceToTarget, hitVecOnScaffold)
            return ScaffoldSite(candidateScaffoldPos, anchorFace, targetPlacement)
        }

        return null
    }
}
