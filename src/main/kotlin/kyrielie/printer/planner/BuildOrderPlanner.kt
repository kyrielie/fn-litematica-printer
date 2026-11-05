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

    private val ATTACHED_BLOCK_PREFIXES = setOf(
        "minecraft:torch",
        "minecraft:wall_torch",
        "minecraft:redstone_wire",
        "minecraft:repeater",
        "minecraft:comparator",
        "minecraft:lever",
        "minecraft:stone_button",
        "minecraft:oak_button",
        "minecraft:ladder",
        "minecraft:rail",
        "minecraft:powered_rail",
        "minecraft:detector_rail",
        "minecraft:activator_rail"
    )

    private val GRAVITY_BLOCKS = setOf(
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:gravel",
        "minecraft:anvil",
        "minecraft:chipped_anvil",
        "minecraft:damaged_anvil"
    )

    fun isAttachedBlock(blockId: String): Boolean {
        val clean = blockId.lowercase()
        return ATTACHED_BLOCK_PREFIXES.any { clean.startsWith(it) }
    }

    fun isGravityBlock(blockId: String): Boolean {
        val clean = blockId.lowercase()
        return GRAVITY_BLOCKS.contains(clean) || clean.endsWith("_concrete_powder")
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
            // 0. Direct Crosshair Target Priority (if crosshair is aimed directly at a candidate)
            val aIsCrosshair = crosshairX != null && a.x == crosshairX && a.y == crosshairY && a.z == crosshairZ
            val bIsCrosshair = crosshairX != null && b.x == crosshairX && b.y == crosshairY && b.z == crosshairZ
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
