package kyrielie.printer.client.player

import kyrielie.printer.util.wrapDegrees
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Protocol safety guard for Minecraft 26.2/26.3 movement packets.
 *
 * Direct contract enforcer:
 * In Minecraft 26.2+, sending more than ONE position-bearing packet
 * (ServerboundMovePlayerPacket.Pos or PosRot) in the same client tick
 * causes server-side heuristic kicks or "disconnect.flying".
 *
 * When camera aiming updates are emitted by the printer:
 * 1. Downgraded to look-only packets (ServerboundMovePlayerPacket.Rot).
 * 2. Capped by angular epsilon threshold to prevent packet flooding on consecutive placements.
 * 3. Player entity rotation (setYRot/setXRot) is kept in sync with the sent packet to prevent
 *    whiplash snaps on subsequent client ticks.
 */
object ProtocolMovementGuard {

    private val logger = LoggerFactory.getLogger("Printer/ProtocolMovementGuard")
    private const val ROTATION_EPSILON_DEGREES = 0.5f

    // Sliding 100ms packet limiter (Disc-Jockey model)
    private const val WINDOW_MS = 100L
    private const val HARD_PACKET_LIMIT = 45
    @Volatile private var windowStartMs: Long = 0L
    @Volatile private var packetsInWindow: Int = 0

    @Volatile
    private var positionSentThisTick: Boolean = false

    @Volatile
    private var lastSentYaw: Float = Float.NaN

    @Volatile
    private var lastSentPitch: Float = Float.NaN

    fun onClientTickEnd() {
        positionSentThisTick = false
    }

    fun observeOutgoing(packet: Packet<*>) {
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= WINDOW_MS) {
            windowStartMs = now
            packetsInWindow = 0
        }
        packetsInWindow++

        if (packet is ServerboundClientTickEndPacket) {
            positionSentThisTick = false
        } else if (packet is ServerboundMovePlayerPacket) {
            if (packet.hasPosition()) {
                positionSentThisTick = true
            }
            if (packet.hasRotation()) {
                val yaw = packet.getYRot(if (lastSentYaw.isFinite()) lastSentYaw else 0f)
                val pitch = packet.getXRot(if (lastSentPitch.isFinite()) lastSentPitch else 0f)
                if (yaw.isFinite()) lastSentYaw = yaw
                if (pitch.isFinite()) lastSentPitch = pitch
            }
        }
    }

    fun isPositionSentThisTick(): Boolean = positionSentThisTick

    /**
     * True if outgoing packet velocity in the last 100ms exceeds safety limits (Disc-Jockey model).
     */
    fun isPacketThrottled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= WINDOW_MS) {
            return false
        }
        return packetsInWindow >= HARD_PACKET_LIMIT
    }

    fun shouldSendRotation(yaw: Float, pitch: Float): Boolean {
        if (lastSentYaw.isNaN() || lastSentPitch.isNaN()) return true
        val yawDiff = abs(wrapDegrees(yaw - lastSentYaw))
        val pitchDiff = abs(pitch - lastSentPitch)
        return yawDiff > ROTATION_EPSILON_DEGREES || pitchDiff > ROTATION_EPSILON_DEGREES
    }
}
