package kyrielie.printer.planner

import kyrielie.printer.model.PlacementTarget
import kyrielie.printer.util.deltaToYawPitch
import kyrielie.printer.util.wrapDegrees
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure algorithmic build-order sorter.
 *
 * Implements:
 * 1. Ascending Y-layer sorting (build from ground up to satisfy gravity/support).
 * 2. Dependency ordering (solid base blocks before attached decorations/dust/torches).
 * 3. Proximity to player's look vector (candidates closest to crosshair placed first).
 * 4. Horizontal distance to player.
 */
object BuildOrderPlanner {

    fun cleanBlockId(raw: String): String {
        var s = raw.lowercase().trim()
        if (s.startsWith("block{") && s.endsWith("}")) {
            s = s.substring(6, s.length - 1).trim()
        }
        if (!s.contains(":")) {
            s = "minecraft:$s"
        }
        return s
    }

    fun isAttachedBlock(rawBlockId: String): Boolean {
        val clean = cleanBlockId(rawBlockId)
        val path = clean.substringAfter(':')

        // Guard against false positive on decorative plants
        if (path == "torchflower" || path == "torchflower_crop") return false

        return path.endsWith("_button") ||
               path.endsWith("_pressure_plate") ||
               path.endsWith("_carpet") ||
               path.endsWith("_banner") ||
               path.endsWith("_sign") ||
               path.endsWith("_rail") ||
               path == "redstone_wire" ||
               path == "repeater" ||
               path == "comparator" ||
               path == "lever" ||
               path == "ladder" ||
               path == "tripwire_hook" ||
               path == "torch" ||
               path == "wall_torch" ||
               path == "soul_torch" ||
               path == "soul_wall_torch" ||
               path == "redstone_torch" ||
               path == "redstone_wall_torch"
    }

    fun isGravityBlock(rawBlockId: String): Boolean {
        val clean = cleanBlockId(rawBlockId)
        val path = clean.substringAfter(':')
        return path == "sand" ||
               path == "red_sand" ||
               path == "gravel" ||
               path == "anvil" ||
               path == "chipped_anvil" ||
               path == "damaged_anvil" ||
               path.endsWith("_concrete_powder") ||
               path == "dragon_egg" ||
               path == "pointed_dripstone"
    }

    /**
     * Sorts placement candidates in physical build order:
     * - Primary: Ascending Y (ground up).
     * - Secondary: Structural type (solid bases before attached decorations, supporting blocks before gravity blocks).
     * - Tertiary: Angular proximity to player's current view vector (aimed/crosshair candidates first).
     * - Quaternary: Horizontal distance to player position (closest first).
     */
    fun sortCandidates(
        candidates: List<PlacementTarget>,
        playerX: Double,
        playerY: Double,
        playerZ: Double,
        playerYaw: Float = 0f,
        playerPitch: Float = 0f,
        eyeY: Double = playerY + 1.62,
        crosshairX: Int? = null,
        crosshairY: Int? = null,
        crosshairZ: Int? = null
    ): List<PlacementTarget> {
        val comparator = Comparator<PlacementTarget> { a, b ->
            // 0. Direct Crosshair Target Priority (if crosshair is aimed directly at candidate or adjacent place-against face)
            val hasCrosshair = crosshairX != null && crosshairY != null && crosshairZ != null
            val aIsCrosshair = hasCrosshair && (
                (a.x == crosshairX && a.y == crosshairY && a.z == crosshairZ) ||
                (abs(a.x - crosshairX) + abs(a.y - crosshairY) + abs(a.z - crosshairZ) <= 1)
            )
            val bIsCrosshair = hasCrosshair && (
                (b.x == crosshairX && b.y == crosshairY && b.z == crosshairZ) ||
                (abs(b.x - crosshairX) + abs(b.y - crosshairY) + abs(b.z - crosshairZ) <= 1)
            )
            if (aIsCrosshair != bIsCrosshair) {
                return@Comparator if (aIsCrosshair) -1 else 1
            }

            // 1. Layer-by-layer (Ascending Y)
            if (a.y != b.y) {
                return@Comparator a.y.compareTo(b.y)
            }

            // 2. Structural dependency within the same layer
            val aAttached = isAttachedBlock(a.blockId)
            val bAttached = isAttachedBlock(b.blockId)
            if (aAttached != bAttached) {
                // False (solid) comes before True (attached)
                return@Comparator aAttached.compareTo(bAttached)
            }

            val aGravity = isGravityBlock(a.blockId)
            val bGravity = isGravityBlock(b.blockId)
            if (aGravity != bGravity) {
                // False (solid support) comes before True (gravity dependent)
                return@Comparator aGravity.compareTo(bGravity)
            }

            // 3. Angular proximity to player's view direction
            val (aYaw, aPitch) = deltaToYawPitch(a.x + 0.5 - playerX, a.y + 0.5 - eyeY, a.z + 0.5 - playerZ)
            val aYawDiff = abs(wrapDegrees(aYaw - playerYaw))
            val aPitchDiff = abs(aPitch - playerPitch)
            val aAngleDelta = sqrt(aYawDiff * aYawDiff + aPitchDiff * aPitchDiff)

            val (bYaw, bPitch) = deltaToYawPitch(b.x + 0.5 - playerX, b.y + 0.5 - eyeY, b.z + 0.5 - playerZ)
            val bYawDiff = abs(wrapDegrees(bYaw - playerYaw))
            val bPitchDiff = abs(bPitch - playerPitch)
            val bAngleDelta = sqrt(bYawDiff * bYawDiff + bPitchDiff * bPitchDiff)

            val angleCmp = aAngleDelta.compareTo(bAngleDelta)
            if (angleCmp != 0) {
                return@Comparator angleCmp
            }

            // 4. Horizontal distance to player
            val aDist = (a.x + 0.5 - playerX).let { it * it } + (a.z + 0.5 - playerZ).let { it * it }
            val bDist = (b.x + 0.5 - playerX).let { it * it } + (b.z + 0.5 - playerZ).let { it * it }
            aDist.compareTo(bDist)
        }

        return candidates.sortedWith(comparator)
    }
}
