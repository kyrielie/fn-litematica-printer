package kyrielie.printer.client.player

import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolMovementGuardTest {

    @Test
    fun testInitialRotationAlwaysSent() {
        // When last sent yaw/pitch is uninitialized (NaN), shouldSendRotation must return true
        assertTrue(ProtocolMovementGuard.shouldSendRotation(0f, 0f))
        assertTrue(ProtocolMovementGuard.shouldSendRotation(180f, 45f))
    }

    @Test
    fun testRotationObservationAndEpsilon() {
        val rotPacket = ServerboundMovePlayerPacket.Rot(100f, 30f, true, false)
        ProtocolMovementGuard.observeOutgoing(rotPacket)

        // Exact same rotation should not be resent
        assertFalse(ProtocolMovementGuard.shouldSendRotation(100f, 30f))

        // Tiny delta <= 0.5 degrees should be filtered
        assertFalse(ProtocolMovementGuard.shouldSendRotation(100.3f, 30.2f))
        assertFalse(ProtocolMovementGuard.shouldSendRotation(99.7f, 29.8f))

        // Delta > 0.5 degrees in yaw or pitch should be sent
        assertTrue(ProtocolMovementGuard.shouldSendRotation(100.6f, 30f))
        assertTrue(ProtocolMovementGuard.shouldSendRotation(100f, 30.6f))
        assertTrue(ProtocolMovementGuard.shouldSendRotation(110f, 30f))
    }

    @Test
    fun testRotationAngleWrapAroundBoundary() {
        // Aiming near +180 deg
        val rotPacket = ServerboundMovePlayerPacket.Rot(179.9f, 0f, true, false)
        ProtocolMovementGuard.observeOutgoing(rotPacket)

        // Sweeping slightly across to -179.9 deg: angular delta is only 0.2 deg!
        // Without proper angle wrapping, |(-179.9) - 179.9| = 359.8 deg (false positive trigger)
        assertFalse(
            ProtocolMovementGuard.shouldSendRotation(-179.9f, 0f),
            "Small sweep across +-180 degree boundary must wrap and be filtered by epsilon"
        )

        // Substantial change across boundary: 179.9 deg to -160.0 deg is ~20 deg change
        assertTrue(ProtocolMovementGuard.shouldSendRotation(-160.0f, 0f))
    }

    @Test
    fun testPositionSentFlagAndTickEndReset() {
        val posPacket = ServerboundMovePlayerPacket.Pos(0.0, 64.0, 0.0, true, false)
        ProtocolMovementGuard.observeOutgoing(posPacket)
        assertTrue(ProtocolMovementGuard.isPositionSentThisTick())

        ProtocolMovementGuard.onClientTickEnd()
        assertFalse(ProtocolMovementGuard.isPositionSentThisTick())

        // Also test ServerboundClientTickEndPacket resets it
        ProtocolMovementGuard.observeOutgoing(posPacket)
        assertTrue(ProtocolMovementGuard.isPositionSentThisTick())
        ProtocolMovementGuard.observeOutgoing(ServerboundClientTickEndPacket.INSTANCE)
        assertFalse(ProtocolMovementGuard.isPositionSentThisTick())
    }

    @Test
    fun testPacketLimiterWindow() {
        val tickPacket = ServerboundClientTickEndPacket.INSTANCE
        // Initially not throttled
        assertFalse(ProtocolMovementGuard.isPacketThrottled())

        // Send 50 packets to trip the 45 packet limit
        repeat(50) {
            ProtocolMovementGuard.observeOutgoing(tickPacket)
        }
        assertTrue(ProtocolMovementGuard.isPacketThrottled())
    }
}
