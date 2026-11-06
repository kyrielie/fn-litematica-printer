package kyrielie.printer.client.guides

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.config.ScaffoldPreferredFace
import kyrielie.printer.client.player.FaceRaycaster
import kyrielie.printer.client.player.VisiblePlacementFace
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
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
     * block can be legally placed against existing solid geometry (Phase 7).
     */
    fun findScaffoldSite(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        maxReach: Double = 4.5
    ): ScaffoldSite? {
        val schemWorld = LitematicaBridge.getSchematicWorld()

        // Phase 7: Order candidate directions according to user preference
        val dirs = when (ConfigManager.config.scaffoldPreferredFace) {
            ScaffoldPreferredFace.UNDERSIDE -> listOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP)
            ScaffoldPreferredFace.TOP -> listOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN)
            ScaffoldPreferredFace.INTERIOR_AUTO -> {
                Direction.entries.sortedByDescending { dir ->
                    val checkPos = targetPos.relative(dir)
                    // Count adjacent schematic air blocks to locate hollow interior
                    if (schemWorld != null) {
                        Direction.entries.count { schemWorld.getBlockState(checkPos.relative(it)).isAir }
                    } else 0
                }
            }
            ScaffoldPreferredFace.ANY -> Direction.entries.toList()
        }

        for (dir in dirs) {
            val candidateScaffoldPos = targetPos.relative(dir)

            // 1. Scaffold position must be empty/air in the real world
            val currentScaffoldState = world.getBlockState(candidateScaffoldPos)
            if (!currentScaffoldState.isAir && !currentScaffoldState.canBeReplaced()) continue

            // 2. Schematic collision guard: Never place scaffold inside a real schematic block!
            if (schemWorld != null) {
                val schemState = schemWorld.getBlockState(candidateScaffoldPos)
                if (!schemState.isAir) continue
            }

            // 3. Must NOT collide with player bounding box
            if (player.boundingBox.intersects(AABB(candidateScaffoldPos))) continue

            // 4. Can the scaffold block be placed against existing solid geometry (NOT targetPos)?
            val anchorFace = FaceRaycaster.findPlacementFace(world, player, eyePos, candidateScaffoldPos, maxReach)
                ?: continue

            if (anchorFace.placeAgainstPos == targetPos) continue // Cannot place scaffold against nonexistent target

            // 5. Does the scaffold provide a legal contact face toward targetPos?
            val faceToTarget = dir.opposite
            val hitVecOnScaffold = when (faceToTarget) {
                Direction.UP    -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 1.0, candidateScaffoldPos.z + 0.5)
                Direction.DOWN  -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y.toDouble(), candidateScaffoldPos.z + 0.5)
                Direction.NORTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z.toDouble())
                Direction.SOUTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 1.0)
                Direction.WEST  -> Vec3(candidateScaffoldPos.x.toDouble(), candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
                Direction.EAST  -> Vec3(candidateScaffoldPos.x + 1.0, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
            }

            if (hitVecOnScaffold.distanceTo(eyePos) > maxReach) continue

            val targetPlacement = VisiblePlacementFace(candidateScaffoldPos, faceToTarget, hitVecOnScaffold)
            return ScaffoldSite(candidateScaffoldPos, anchorFace, targetPlacement)
        }

        return null
    }
}
