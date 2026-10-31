package com.mrstudios.trades.trade;

import java.util.List;

/**
 * Predicts whether items fit into an inventory, the way {@code Inventory#addItem} places them:
 * each incoming stack first tops up similar partial stacks, then fills empty slots, never going
 * past {@code min(item max stack, inventory max stack)}. Works on copies; the inputs are never
 * changed.
 */
public final class InventorySimulator {

    private InventorySimulator() {
    }

    /**
     * @param contents the receiver's storage slots; null means an empty slot
     * @param incoming the stacks to add; null entries are ignored
     * @param inventoryMaxStack the inventory's own stack limit ({@code Inventory#getMaxStackSize()})
     */
    public static boolean fits(List<? extends StackView> contents, List<? extends StackView> incoming,
            int inventoryMaxStack) {
        return leftover(contents, incoming, inventoryMaxStack) == 0;
    }

    /** How many items of {@code incoming} (counted one by one) would not fit. */
    public static int leftover(List<? extends StackView> contents, List<? extends StackView> incoming,
            int inventoryMaxStack) {
        if (inventoryMaxStack < 1) {
            throw new IllegalArgumentException("inventoryMaxStack must be at least 1");
        }
        int size = contents.size();
        StackView[] kinds = new StackView[size];
        int[] amounts = new int[size];
        for (int i = 0; i < size; i++) {
            StackView slot = contents.get(i);
            if (slot != null && slot.amount() > 0) {
                kinds[i] = slot;
                amounts[i] = slot.amount();
            }
        }
        int leftover = 0;
        for (StackView item : incoming) {
            if (item == null || item.amount() <= 0) {
                continue;
            }
            int limit = Math.max(1, Math.min(item.maxStackSize(), inventoryMaxStack));
            int remaining = item.amount();
            // Top up similar partial stacks, in slot order.
            for (int i = 0; i < size && remaining > 0; i++) {
                if (kinds[i] != null && amounts[i] < limit && kinds[i].isSimilar(item)) {
                    int moved = Math.min(limit - amounts[i], remaining);
                    amounts[i] += moved;
                    remaining -= moved;
                }
            }
            // Then empty slots, one full stack at a time.
            for (int i = 0; i < size && remaining > 0; i++) {
                if (kinds[i] == null) {
                    int moved = Math.min(limit, remaining);
                    kinds[i] = item;
                    amounts[i] = moved;
                    remaining -= moved;
                }
            }
            leftover += remaining;
        }
        return leftover;
    }
}
