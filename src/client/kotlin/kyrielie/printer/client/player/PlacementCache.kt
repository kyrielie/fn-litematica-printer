package kyrielie.printer.client.player

import net.minecraft.core.BlockPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Cooldown tracking for recently placed blocks.
 *
 * In multiplayer, client-side block placement packets (useItemOn) require
 * network round-trip time before the server confirms and updates world state.
 *
 * This cache tracks positions that currently have an in-flight placement packet
 * awaiting server confirmation. The cooldown is kept short (400ms) to prevent
 * duplicate packet spam while allowing rapid building.
 */
object PlacementCache {

    private val cache = ConcurrentHashMap<BlockPos, Long>()
    const val DEFAULT_TIMEOUT_MS = 400L

    fun isCached(pos: BlockPos): Boolean {
        val expiry = cache[pos] ?: return false
        val now = System.currentTimeMillis()
        if (now >= expiry) {
            cache.remove(pos)
            return false
        }
        return true
    }

    fun markPlaced(pos: BlockPos, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        cache[pos] = System.currentTimeMillis() + timeoutMs
    }

    fun remove(pos: BlockPos) {
        cache.remove(pos)
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { now >= it.value }
    }

    fun clear() {
        cache.clear()
    }
}
