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

    private var lastRestockTimestamp: Long = 0L

    /**
     * Pure query checking if an item matching [itemPredicate] is present in player's inventory (G-3).
     * Emits zero packets and performs zero hotbar slot mutations.
     */
    fun hasItem(player: LocalPlayer, itemPredicate: (ItemStack) -> Boolean): Boolean {
        val offhand = player.offhandItem
        if (!offhand.isEmpty && offhand.count > 0 && itemPredicate(offhand)) return true

        val mainHand = player.mainHandItem
        if (!mainHand.isEmpty && mainHand.count > 0 && itemPredicate(mainHand)) return true

        val inventory: Inventory = player.inventory
        for (slot in 0..35) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.count > 0 && itemPredicate(stack)) return true
        }
        return false
    }

    /** Overload query checking if [item] is available in inventory */
    fun hasItem(player: LocalPlayer, item: Item): Boolean =
        hasItem(player) { !it.isEmpty && it.count > 0 && it.item == item }

    /** Determines which hand should be used for placement without mutating slots */
    fun getPreferredHand(player: LocalPlayer, item: Item): InteractionHand {
        val offhand = player.offhandItem
        if (!offhand.isEmpty && offhand.count > 0 && offhand.item == item) {
            return InteractionHand.OFF_HAND
        }
        return InteractionHand.MAIN_HAND
    }

    /**
     * Ensures an item matching [itemPredicate] is held in the player's main hand or offhand.
     * Executed strictly inside executeAction() when placement is guaranteed.
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
                if (inventory.selectedSlot != slot) {
                    inventory.selectedSlot = slot
                    player.connection.send(ServerboundSetCarriedItemPacket(slot))
                    PrinterController.resetRollbackTracking()
                }
                return InteractionHand.MAIN_HAND
            }
        }

        // 4. Check main inventory slots (9..35)
        for (slot in 9..35) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && stack.count > 0 && itemPredicate(stack)) {
                val gameMode = mc.gameMode ?: return null

                // Ensure no conflicting container screen is currently open
                if (player.containerMenu !== player.inventoryMenu) {
                    return null
                }

                val now = System.currentTimeMillis()
                if (now - lastRestockTimestamp < 100L) {
                    return null
                }
                lastRestockTimestamp = now

                // Select the best hotbar target slot to avoid kicking useful tools or pending blocks
                val hotbarTargetSlot = selectBestHotbarSlot(inventory)
                val containerSlot = slot

                PrinterController.resetRollbackTracking()
                gameMode.handleContainerInput(
                    player.inventoryMenu.containerId,
                    containerSlot,
                    hotbarTargetSlot,
                    ContainerInput.SWAP,
                    player
                )

                // Select the target hotbar slot immediately so the player is holding the swapped item
                if (inventory.selectedSlot != hotbarTargetSlot) {
                    inventory.selectedSlot = hotbarTargetSlot
                    player.connection.send(ServerboundSetCarriedItemPacket(hotbarTargetSlot))
                    PrinterController.resetRollbackTracking()
                }

                // Pause 1 tick for container inventory synchronization
                return null
            }
        }

        return null
    }

    /**
     * Finds the best hotbar slot (0..8) to receive a restocked item:
     * 1. The currently selected hotbar slot if it is empty.
     * 2. Any other empty hotbar slot (count == 0 or isEmpty).
     * 3. A non-tool slot (not damageable tool/weapon/shears).
     * 4. Fallback to currently selected slot.
     */
    private fun selectBestHotbarSlot(inventory: Inventory): Int {
        val active = inventory.selectedSlot
        val activeStack = inventory.getItem(active)
        if (activeStack.isEmpty || activeStack.count == 0) {
            return active
        }

        // Search for empty hotbar slots
        for (slot in 0..8) {
            val s = inventory.getItem(slot)
            if (s.isEmpty || s.count == 0) {
                return slot
            }
        }

        // Search for non-tool slots (damageable items or items with maxDamage > 1)
        for (slot in 0..8) {
            val s = inventory.getItem(slot)
            val isTool = s.isDamageableItem || s.maxDamage > 1
            if (!isTool) {
                return slot
            }
        }

        return active
    }

    /** Overload by Item instance: ensures non-empty and matching item */
    fun prepareItem(mc: Minecraft, player: LocalPlayer, item: Item): InteractionHand? =
        prepareItem(mc, player) { !it.isEmpty && it.count > 0 && it.item == item }
}
