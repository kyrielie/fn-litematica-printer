package kyrielie.printer.model

/**
 * Pure data model representing a target block to be placed.
 * Independent of Minecraft registries to ensure 100% JVM testability (Decision D1).
 */
data class PlacementTarget(
    val x: Int,
    val y: Int,
    val z: Int,
    val blockId: String,
    val properties: Map<String, String> = emptyMap(),
    val priority: Int = 0
) {
    fun squaredDistanceTo(px: Double, py: Double, pz: Double): Double {
        val dx = (x + 0.5) - px
        val dy = (y + 0.5) - py
        val dz = (z + 0.5) - pz
        return dx * dx + dy * dy + dz * dz
    }
}
