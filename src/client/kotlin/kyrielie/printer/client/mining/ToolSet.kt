package kyrielie.printer.client.mining

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState

/**
 * Baritone-derived ToolSet mining engine (Phase 9).
 * Computes optimal breaking speed across hotbar items and guarantees itemSaver durability protection.
 */
class ToolSet(
    private val player: LocalPlayer,
    private val itemSaver: Boolean = true,
    private val itemSaverThreshold: Int = 10
) {

    /**
     * Determines the optimal hotbar slot (0..8) to break [state], strictly honoring [itemSaver].
     */
    fun getBestSlot(state: BlockState, pos: BlockPos = BlockPos.ZERO, level: BlockGetter = player.level()): Int {
        var bestSlot = player.inventory.selectedSlot
        var highestSpeed = Double.NEGATIVE_INFINITY
        var lowestCost = Int.MAX_VALUE

        for (i in 0..8) {
            val itemStack = player.inventory.getItem(i)
            if (itemStack.isEmpty) continue

            // ItemSaver durability threshold protection (Phase 9)
            if (itemSaver && itemStack.maxDamage > 1) {
                val currentDamage = itemStack.damageValue
                if (currentDamage + itemSaverThreshold >= itemStack.maxDamage) {
                    continue
                }
            }

            val speed = calculateSpeedVsBlock(itemStack, state, level, pos)
            val cost = getMaterialCost(itemStack)
            if (speed > highestSpeed) {
                highestSpeed = speed
                bestSlot = i
                lowestCost = cost
            } else if (speed == highestSpeed && cost < lowestCost) {
                highestSpeed = speed
                bestSlot = i
                lowestCost = cost
            }
        }
        return bestSlot
    }

    private fun getMaterialCost(stack: ItemStack): Int {
        val name = stack.item.descriptionId
        return when {
            name.contains("netherite") -> 4
            name.contains("diamond") -> 3
            name.contains("iron") -> 2
            name.contains("golden") -> 1
            name.contains("stone") -> 1
            name.contains("wooden") -> 0
            else -> 0
        }
    }

    /**
     * Calculates block mining speed in progress-per-tick using Minecraft's exact break formula.
     */
    fun calculateSpeedVsBlock(
        item: ItemStack,
        state: BlockState,
        level: BlockGetter = player.level(),
        pos: BlockPos = BlockPos.ZERO
    ): Double {
        val hardness = try {
            state.getDestroySpeed(level, pos)
        } catch (_: Exception) {
            return -1.0
        }
        if (hardness < 0f) return -1.0 // Unbreakable

        var speed = item.getDestroySpeed(state).toDouble()
        speed /= hardness.toDouble()

        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty && item.isCorrectToolForDrops(state))) {
            speed /= 30.0
        } else {
            speed /= 100.0
        }

        return speed * potionAmplifier()
    }

    private fun potionAmplifier(): Double {
        var speed = 1.0
        val haste = player.getEffect(MobEffects.HASTE)
        if (haste != null) {
            speed *= 1.0 + (haste.amplifier + 1) * 0.2
        }
        val fatigue = player.getEffect(MobEffects.MINING_FATIGUE)
        if (fatigue != null) {
            speed *= when (fatigue.amplifier) {
                0 -> 0.3
                1 -> 0.09
                2 -> 0.0027
                else -> 0.00081
            }
        }
        return speed
    }
}
