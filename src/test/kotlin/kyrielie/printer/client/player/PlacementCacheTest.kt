package kyrielie.printer.client.player

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlacementCacheTest {

    @BeforeEach
    fun setUp() {
        PlacementCache.clear()
    }

    @Test
    fun testComputeDynamicLease() {
        // Ping <= 0 should default to 50ms base: maxOf(400, 50 * 2.5 + 200 = 325) -> 400
        assertEquals(400L, PlacementCache.computeDynamicLease(0))
        assertEquals(400L, PlacementCache.computeDynamicLease(-10))

        // Normal low ping (50ms): 50 * 2.5 + 200 = 325 -> coerced to min 400
        assertEquals(400L, PlacementCache.computeDynamicLease(50))

        // Medium ping (100ms): 100 * 2.5 + 200 = 450 -> 450
        assertEquals(450L, PlacementCache.computeDynamicLease(100))

        // High ping (300ms): 300 * 2.5 + 200 = 950 -> 950
        assertEquals(950L, PlacementCache.computeDynamicLease(300))

        // Extreme ping (2000ms): 2000 * 2.5 + 200 = 5200 -> clamped at max 3000
        assertEquals(3000L, PlacementCache.computeDynamicLease(2000))
    }

    @Test
    fun testMarkPlacedAndIsCached() {
        val pos = BlockPos(10, 64, 20)
        assertFalse(PlacementCache.isCached(pos), "Pos should not be cached initially")

        PlacementCache.markPlaced(pos, timeoutMs = 1000L)
        assertTrue(PlacementCache.isCached(pos), "Pos should be cached within timeout")

        val otherPos = BlockPos(10, 64, 21)
        assertFalse(PlacementCache.isCached(otherPos), "Different pos should not be cached")
    }

    @Test
    fun testRecordFailureBackoff() {
        val pos = BlockPos(5, 70, 5)

        // 1st failure: short lease (250ms)
        PlacementCache.recordFailure(pos)
        assertTrue(PlacementCache.isCached(pos))

        // 2nd failure: short lease (250ms)
        PlacementCache.recordFailure(pos)
        assertTrue(PlacementCache.isCached(pos))

        // 3rd failure: triggers 2000ms backoff and resets counter
        PlacementCache.recordFailure(pos)
        assertTrue(PlacementCache.isCached(pos))

        // 4th failure: starts fresh count at 1 (short lease 250ms)
        PlacementCache.recordFailure(pos)
        assertTrue(PlacementCache.isCached(pos))
    }

    @Test
    fun testRecordSuccessClearsFailures() {
        val pos = BlockPos(100, 50, 100)

        // Record 2 failures
        PlacementCache.recordFailure(pos)
        PlacementCache.recordFailure(pos)

        // Success clears the failure streak
        PlacementCache.recordSuccess(pos)

        // Next failure should be treated as failure #1, not failure #3
        PlacementCache.recordFailure(pos)
        // Failure count should now be 1, not 3
        // If we record one more, it's #2 (still not backoff)
        PlacementCache.recordFailure(pos)
        assertTrue(PlacementCache.isCached(pos))
    }

    @Test
    fun testRemoveAndClear() {
        val pos1 = BlockPos(1, 2, 3)
        val pos2 = BlockPos(4, 5, 6)

        PlacementCache.markPlaced(pos1, 5000L)
        PlacementCache.markPlaced(pos2, 5000L)
        assertTrue(PlacementCache.isCached(pos1))
        assertTrue(PlacementCache.isCached(pos2))

        PlacementCache.remove(pos1)
        assertFalse(PlacementCache.isCached(pos1))
        assertTrue(PlacementCache.isCached(pos2))

        PlacementCache.clear()
        assertFalse(PlacementCache.isCached(pos2))
    }

    @Test
    fun testCleanupRemovesExpired() {
        val expiredPos = BlockPos(10, 10, 10)
        // Set with negative timeout so it is immediately expired
        PlacementCache.markPlaced(expiredPos, -100L)

        // Pos is expired, isCached should clean it up and return false
        assertFalse(PlacementCache.isCached(expiredPos))
    }
}
