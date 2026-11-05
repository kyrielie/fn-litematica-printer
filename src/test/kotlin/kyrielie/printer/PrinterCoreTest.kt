package kyrielie.printer

import kyrielie.printer.model.PlacementTarget
import kyrielie.printer.planner.BuildOrderPlanner
import kyrielie.printer.planner.CarpetProtocol
import kyrielie.printer.util.deltaToYawPitch
import kyrielie.printer.util.orderByAngularProximity
import kyrielie.printer.util.wrapDegrees
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrinterCoreTest {

    @Test
    fun testWrapDegrees() {
        assertEquals(0f, wrapDegrees(0f))
        assertEquals(-180f, wrapDegrees(180f))
        assertEquals(90f, wrapDegrees(90f))
        assertEquals(-90f, wrapDegrees(270f))
        assertEquals(45f, wrapDegrees(405f))
        assertEquals(-45f, wrapDegrees(-405f))
    }

    @Test
    fun testDeltaToYawPitch() {
        // Looking directly North (negative Z) wraps to -180 degrees in [-180, 180)
        val (yawNorth, pitchHorizon) = deltaToYawPitch(0.0, 0.0, -5.0)
        assertEquals(-180f, yawNorth, 0.01f)
        assertEquals(0f, pitchHorizon, 0.01f)

        // Looking directly South (positive Z) is 0 degrees
        val (yawSouth, _) = deltaToYawPitch(0.0, 0.0, 5.0)
        assertEquals(0f, yawSouth, 0.01f)

        // Looking directly East (positive X) is -90 degrees
        val (yawEast, _) = deltaToYawPitch(5.0, 0.0, 0.0)
        assertEquals(-90f, yawEast, 0.01f)

        // Looking directly West (negative X) is 90 degrees
        val (yawWest, _) = deltaToYawPitch(-5.0, 0.0, 0.0)
        assertEquals(90f, yawWest, 0.01f)

        // Looking straight down
        val (_, pitchDown) = deltaToYawPitch(0.0, -5.0, 0.0)
        assertEquals(90f, pitchDown, 0.01f)

        // Looking straight up
        val (_, pitchUp) = deltaToYawPitch(0.0, 5.0, 0.0)
        assertEquals(-90f, pitchUp, 0.01f)
    }

    @Test
    fun testBuildOrderPlannerAscendingY() {
        val targets = listOf(
            PlacementTarget(0, 10, 0, "minecraft:stone"),
            PlacementTarget(0, 5, 0, "minecraft:stone"),
            PlacementTarget(0, 12, 0, "minecraft:stone")
        )

        val sorted = BuildOrderPlanner.sortCandidates(targets, 0.0, 0.0, 0.0)
        assertEquals(5, sorted[0].y)
        assertEquals(10, sorted[1].y)
        assertEquals(12, sorted[2].y)
    }

    @Test
    fun testBuildOrderPlannerSolidBeforeAttached() {
        val targets = listOf(
            PlacementTarget(0, 5, 0, "minecraft:redstone_wire"),
            PlacementTarget(0, 5, 1, "minecraft:stone"),
            PlacementTarget(0, 5, 2, "minecraft:torch")
        )

        val sorted = BuildOrderPlanner.sortCandidates(targets, 0.0, 5.0, 0.0)
        // Stone must come before redstone_wire and torch
        assertEquals("minecraft:stone", sorted[0].blockId)
    }

    @Test
    fun testBuildOrderPlannerSolidBeforeGravity() {
        val targets = listOf(
            PlacementTarget(0, 5, 0, "minecraft:sand"),
            PlacementTarget(0, 5, 1, "minecraft:dirt"),
            PlacementTarget(0, 5, 2, "minecraft:gravel")
        )

        val sorted = BuildOrderPlanner.sortCandidates(targets, 0.0, 5.0, 0.0)
        assertEquals("minecraft:dirt", sorted[0].blockId)
    }

    @Test
    fun testCarpetProtocolV2EncodingAndDecoding() {
        val blockX = 100
        val rawHitX = 0.5
        val facingId = CarpetProtocol.getFacingId("north") // 2

        val encodedHitX = CarpetProtocol.applyCarpetProtocolV2(blockX, rawHitX, facingId)
        // expected: 100 + 0.5 + 2.0 + (2.0 * 2) = 106.5
        assertEquals(106.5, encodedHitX, 0.001)

        val decodedFacing = CarpetProtocol.decodeCarpetProtocolV2Facing(blockX, encodedHitX)
        assertEquals(facingId, decodedFacing)
    }

    @Test
    fun testOrderByAngularProximity() {
        val items = listOf("A", "B", "C")
        val angles = mapOf(
            "A" to Pair(10f, 0f),
            "B" to Pair(90f, 0f),
            "C" to Pair(20f, 0f)
        )

        val sorted = orderByAngularProximity(items, startYaw = 0f, startPitch = 0f) { angles[it] }
        // From 0, closest is A (10), then C (20), then B (90)
        assertEquals(listOf("A", "C", "B"), sorted)
    }

    @Test
    fun testBuildOrderPlannerAngularProximity() {
        val inFront = PlacementTarget(0, 60, 3, "minecraft:glass") // South (yaw 0)
        val toSide = PlacementTarget(-3, 60, 0, "minecraft:glass") // West (yaw 90)
        val behind = PlacementTarget(0, 60, -3, "minecraft:glass") // North (yaw -180)

        val targets = listOf(behind, toSide, inFront)

        // Player looking South (yaw = 0f, pitch = 0f)
        val sorted = BuildOrderPlanner.sortCandidates(
            targets,
            playerX = 0.5,
            playerY = 60.0,
            playerZ = 0.5,
            playerYaw = 0f,
            playerPitch = 0f,
            eyeY = 61.62
        )

        assertEquals(inFront, sorted[0], "Candidate in front of crosshair must be prioritized first")
        assertEquals(toSide, sorted[1], "Candidate to the side must be second")
        assertEquals(behind, sorted[2], "Candidate behind player must be last")
    }

    @Test
    fun testBuildOrderPlannerDirectCrosshairTarget() {
        val inFront = PlacementTarget(0, 60, 3, "minecraft:glass")
        val crosshairTarget = PlacementTarget(-3, 60, 0, "minecraft:glass")
        val targets = listOf(inFront, crosshairTarget)

        // Even if inFront has lower angular delta from player looking south,
        // crosshairTarget matches the exact crosshair ray block and must be #1
        val sorted = BuildOrderPlanner.sortCandidates(
            targets,
            playerX = 0.5,
            playerY = 60.0,
            playerZ = 0.5,
            playerYaw = 0f,
            playerPitch = 0f,
            eyeY = 61.62,
            crosshairX = -3,
            crosshairY = 60,
            crosshairZ = 0
        )

        assertEquals(crosshairTarget, sorted[0], "Exact crosshair target must be prioritized above all others")
    }
}
