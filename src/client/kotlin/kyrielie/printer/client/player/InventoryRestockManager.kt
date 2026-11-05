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
     * If in main inventory (9..35), swaps with current selected slot via [handleContainerInput] and returns [InteractionHand.MAIN_HAND].
     * If not found, returns null.
     */
    fun prepareItem(
        mc: Minecraft,
        player: LocalPlayer,
        itemPredicate: (ItemStack) -> Boolean
    ): InteractionHand? {
        // 1. Check offhand first
        if (itemPredicate(player.offhandItem)) {
            return InteractionHand.OFF_HAND
        }

        // 2. Check main hand
        if (itemPredicate(player.mainHandItem)) {
            return InteractionHand.MAIN_HAND
        }

        val inventory: Inventory = player.inventory

        // 3. Check hotbar slots (0..8)
        for (slot in 0..8) {
            val stack = inventory.getItem(slot)
            if (itemPredicate(stack)) {
                inventory.selectedSlot = slot
                player.connection.send(ServerboundSetCarriedItemPacket(slot))
                return InteractionHand.MAIN_HAND
            }
        }

        // 4. Check main inventory slots (9..35)
        for (slot in 9..35) {
            val stack = inventory.getItem(slot)
            if (itemPredicate(stack)) {
                val activeHotbarSlot = inventory.selectedSlot
                val gameMode = mc.gameMode ?: return null

                // In Minecraft container slots, player inventory starts at slot 9
                // Slot indices in InventoryMenu: 0=crafting result, 1-4=crafting, 5-8=armor, 9-35=main inv, 36-44=hotbar
                val containerSlot = slot // In standard InventoryMenu, main inv is slots 9..35
                val hotbarTargetSlot = activeHotbarSlot // Button parameter in SWAP represents hotbar slot 0..8

                gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    containerSlot,
                    hotbarTargetSlot,
                    ContainerInput.SWAP,
                    player
                )
                return InteractionHand.MAIN_HAND
            }
        }

        return null
    }

    /** Overload by Item instance */
    fun prepareItem(mc: Minecraft, player: LocalPlayer, item: Item): InteractionHand? =
        prepareItem(mc, player) { it.`is`(item) }
}
