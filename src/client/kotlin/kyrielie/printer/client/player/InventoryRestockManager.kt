package kyrielie.printer.client.player

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

object InventoryRestockManager {

    /**
     * Ensures an item matching [itemPredicate] is held in the player's main hand or offhand.
     * If present in the offhand, returns [InteractionHand.OFF_HAND].
     * If in hotbar (0..8), sends [ServerboundSetCarriedItemPacket] and returns [InteractionHand.MAIN_HAND].
     * If in main inventory (9..35), swaps with current selected slot via [handleContainerInput] and returns null (pauses 1 tick for swap).
     * If not found, returns null.
     */
    fun prepareItem(
        mc: Minecraft,
        player: LocalPlayer,
        itemPredicate: (ItemStack) -> Boolean
    ): InteractionHand? {
        // 1. Check offhand first
        val offhand = player.offhandItem
        if (!offhand.isEmpty && offhand.count > 0 && itemPredicate(offhand)) {
            return InteractionHand.OFF_HAND
        }

        // 2. Check main hand
        val mainHand = player.mainHandItem
        if (!mainHand.isEmpty && mainHand.count > 0 && itemPredicate(mainHand)) {
            return InteractionHand.MAIN_HAND
        }

        val inventory: Inventory = player.inventory

        // 3. Check hotbar slots (0..8)
        for (slot in 0..8) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.count > 0 && itemPredicate(stack)) {
                inventory.selectedSlot = slot
                player.connection.send(ServerboundSetCarriedItemPacket(slot))
                return InteractionHand.MAIN_HAND
            }
        }

        // 4. Check main inventory slots (9..35)
        for (slot in 9..35) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.count > 0 && itemPredicate(stack)) {
                val activeHotbarSlot = inventory.selectedSlot
                val gameMode = mc.gameMode ?: return null

                val containerSlot = slot
                val hotbarTargetSlot = activeHotbarSlot

                gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    containerSlot,
                    hotbarTargetSlot,
                    ContainerInput.SWAP,
                    player
                )
                // Restock swap packet dispatched; pause 1 tick for server to process container change
                return null
            }
        }

        return null
    }

    /** Overload by Item instance: ensures non-empty and matching item */
    fun prepareItem(mc: Minecraft, player: LocalPlayer, item: Item): InteractionHand? =
        prepareItem(mc, player) { !it.isEmpty && it.count > 0 && it.item == item }
}
