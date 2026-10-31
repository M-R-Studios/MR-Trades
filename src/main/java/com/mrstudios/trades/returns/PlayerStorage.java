package com.mrstudios.trades.returns;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Adds items to a player's 36 storage slots only (hotbar and main inventory, never armor or
 * offhand), the same way {@code InventorySimulator} predicts: top up similar partial stacks
 * first, then fill empty slots. Doing it here instead of {@code Inventory#addItem} makes the
 * prediction and the real move follow exactly the same rules on every server.
 */
public final class PlayerStorage {

    public static final int SLOTS = 36;

    private PlayerStorage() {
    }

    /**
     * @return what didn't fit, or null if everything did
     */
    public static ItemStack add(PlayerInventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return null;
        }
        int limit = Math.max(1, Math.min(item.getMaxStackSize(), inventory.getMaxStackSize()));
        int remaining = item.getAmount();
        for (int slot = 0; slot < SLOTS && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing != null && !existing.getType().isAir() && existing.getAmount() < limit
                    && existing.isSimilar(item)) {
                int moved = Math.min(limit - existing.getAmount(), remaining);
                existing.setAmount(existing.getAmount() + moved);
                inventory.setItem(slot, existing);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < SLOTS && remaining > 0; slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(Math.min(limit, remaining));
                inventory.setItem(slot, placed);
                remaining -= placed.getAmount();
            }
        }
        if (remaining == 0) {
            return null;
        }
        ItemStack rest = item.clone();
        rest.setAmount(remaining);
        return rest;
    }
}
