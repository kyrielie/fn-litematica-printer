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
