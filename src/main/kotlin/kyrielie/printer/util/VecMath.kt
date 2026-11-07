package kyrielie.printer.util

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Wraps [degrees] into [-180, 180) range.
 */
fun wrapDegrees(degrees: Float): Float = (((degrees % 360f) + 540f) % 360f) - 180f

/**
 * Converts a 3D vector delta (from eye position to target point) into Minecraft yaw and pitch angles.
 * Returns Pair(yaw, pitch) in degrees.
 */
fun deltaToYawPitch(dx: Double, dy: Double, dz: Double): Pair<Float, Float> {
    val horizontalDist = sqrt(dx * dx + dz * dz)
    val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
    val pitch = Math.toDegrees(-atan2(dy, horizontalDist)).toFloat()
    return Pair(wrapDegrees(yaw), pitch.coerceIn(-90f, 90f))
}

/**
 * Resolves a yaw angle to its corresponding cardinal facing ("north", "south", "east", "west")
 * exactly adhering to Minecraft's Direction.fromYRot() mapping.
 */
fun yawToCardinalDirection(yaw: Float): String {
    val wrapped = wrapDegrees(yaw)
    return when {
        wrapped >= -45f && wrapped < 45f -> "south"
        wrapped >= 45f && wrapped < 135f -> "west"
        wrapped >= -135f && wrapped < -45f -> "east"
        else -> "north"
    }
}

/**
 * Pure feasibility evaluator: determines if aiming at (hitX, hitY, hitZ) from
 * (eyeX, eyeY, eyeZ) naturally derives the required cardinal facing.
 */
fun isPlacementFeasible(
    eyeX: Double, eyeY: Double, eyeZ: Double,
    hitX: Double, hitY: Double, hitZ: Double,
    requiredFacing: String,
    isOppositeLooking: Boolean = false
): Boolean {
    val (yaw, _) = deltaToYawPitch(hitX - eyeX, hitY - eyeY, hitZ - eyeZ)
    val natural = yawToCardinalDirection(yaw)
    val opposite = when (natural) {
        "north" -> "south"
        "south" -> "north"
        "east" -> "west"
        "west" -> "east"
        else -> natural
    }
    val derived = if (isOppositeLooking) opposite else natural
    return derived.equals(requiredFacing, ignoreCase = true)
}

/**
 * Orders [items] greedily by angular proximity: starting from ([startYaw], [startPitch])
 * it repeatedly picks the remaining candidate whose aim direction is closest.
 * Keeps camera sweeps smooth and short during multi-block placements.
 */
fun <T> orderByAngularProximity(
    items: List<T>,
    startYaw: Float,
    startPitch: Float,
    angles: (T) -> Pair<Float, Float>?
): List<T> {
    val withAngles = ArrayList<Pair<T, Pair<Float, Float>>>()
    val without = ArrayList<T>()
    for (item in items) {
        val a = angles(item)
        if (a != null) withAngles += item to a else without += item
    }

    val result = ArrayList<T>(items.size)
    var yaw = startYaw
    var pitch = startPitch
    val remaining = withAngles.toMutableList()
    while (remaining.isNotEmpty()) {
        var bestIdx = 0
        var bestCost = Float.MAX_VALUE
        for (i in remaining.indices) {
            val (ay, ap) = remaining[i].second
            val dy = wrapDegrees(ay - yaw)
            val dp = ap - pitch
            val cost = sqrt(dy * dy + dp * dp)
            if (cost < bestCost) {
                bestCost = cost
                bestIdx = i
            }
        }
        val picked = remaining.removeAt(bestIdx)
        result += picked.first
        yaw = picked.second.first
        pitch = picked.second.second
    }
    result += without
    return result
}
