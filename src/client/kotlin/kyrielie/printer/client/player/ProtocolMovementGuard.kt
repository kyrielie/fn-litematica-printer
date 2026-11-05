package kyrielie.printer.client.player

import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import org.slf4j.LoggerFactory

/**
 * Protocol safety guard for Minecraft 26.2/26.3 movement packets.
 *
 * Direct contract enforcer:
 * In Minecraft 26.2+, sending more than ONE position-bearing packet
 * (ServerboundMovePlayerPacket.Pos or PosRot) in the same client tick
 * causes server-side heuristic kicks or "disconnect.flying".
 *
 * When the player naturally moves/walks while the printer is active,
 * any additional camera aiming updates emitted by the printer are
 * safely downgraded to look-only packets (ServerboundMovePlayerPacket.Rot).
 */
object ProtocolMovementGuard {

    private val logger = LoggerFactory.getLogger("Printer/ProtocolMovementGuard")

    @Volatile
    private var positionSentThisTick: Boolean = false

    @Volatile
    private var lastSentYaw: Float = 0f

    @Volatile
    private var lastSentPitch: Float = 0f

    fun onClientTickEnd() {
        positionSentThisTick = false
    }

    fun observeOutgoing(packet: Packet<*>) {
        if (packet is ServerboundClientTickEndPacket) {
            positionSentThisTick = false
        } else if (packet is ServerboundMovePlayerPacket) {
            if (packet.hasPosition()) {
                positionSentThisTick = true
            }
            if (packet.hasRotation()) {
                val yaw = packet.getYRot(lastSentYaw)
                val pitch = packet.getXRot(lastSentPitch)
                if (yaw.isFinite()) lastSentYaw = yaw
                if (pitch.isFinite()) lastSentPitch = pitch
            }
        }
    }

    fun isPositionSentThisTick(): Boolean = positionSentThisTick

    fun createSafeRotationPacket(
        yaw: Float,
        pitch: Float,
        onGround: Boolean,
        horizontalCollision: Boolean
    ): ServerboundMovePlayerPacket.Rot {
        val safeYaw = if (yaw.isFinite()) yaw else lastSentYaw
        val safePitch = if (pitch.isFinite()) pitch else lastSentPitch
        lastSentYaw = safeYaw
        lastSentPitch = safePitch
        return ServerboundMovePlayerPacket.Rot(safeYaw, safePitch, onGround, horizontalCollision)
    }

    fun sendLookUpdate(
        connection: Connection,
        yaw: Float,
        pitch: Float,
        onGround: Boolean = true,
        horizontalCollision: Boolean = false
    ) {
        val packet = createSafeRotationPacket(yaw, pitch, onGround, horizontalCollision)
        connection.send(packet)
    }
}
