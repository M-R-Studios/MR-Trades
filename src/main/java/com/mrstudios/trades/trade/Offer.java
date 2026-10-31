package com.mrstudios.trades.trade;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * One side's offer: up to {@link #MAX_STACKS} item stacks, an amount of money and a number of XP
 * levels. The items here are the real items, held in escrow: they left the owner's inventory when
 * they were added. Everything handed out is a copy, so no caller can change the offer behind its
 * back. Change it only through {@link TradeSession#changeOffer}, which resets the confirmations.
 */
public final class Offer {

    public static final int MAX_STACKS = 16;

    private final List<ItemStack> items = new ArrayList<>();
    private BigDecimal money = BigDecimal.ZERO;
    private int xpLevels;

    /** Copies of the offered stacks, in display order. */
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }

    /** The offered stacks as views, for {@link InventorySimulator}. */
    public List<StackView> itemViews() {
        return items.stream().<StackView>map(item -> new ItemStackView(item.clone())).toList();
    }

    public int stackCount() {
        return items.size();
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    /** A copy of the stack at {@code index}, or null if there is none. */
    public ItemStack item(int index) {
        return index >= 0 && index < items.size() ? items.get(index).clone() : null;
    }

    public BigDecimal money() {
        return money;
    }

    public int xpLevels() {
        return xpLevels;
    }

    /** True when the offer holds nothing at all: no items, no money, no levels. */
    public boolean isEmpty() {
        return items.isEmpty() && money.signum() == 0 && xpLevels == 0;
    }

    /**
     * How many of {@code item} could still be added: room left in similar stacks, plus whole new
     * stacks while there are fewer than {@link #MAX_STACKS}.
     */
    public int roomFor(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        int limit = limit(item);
        long room = (long) (MAX_STACKS - items.size()) * limit;
        for (ItemStack stack : items) {
            if (stack.isSimilar(item) && stack.getAmount() < limit) {
                room += limit - stack.getAmount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }

    /**
     * Adds {@code moved}, which must already be out of the owner's inventory. Tops up similar
     * stacks first, then starts new ones.
     *
     * @return true if it was added; false (and nothing changed) if it doesn't fit as a whole
     */
    boolean add(ItemStack moved) {
        if (moved == null || moved.getType().isAir() || moved.getAmount() <= 0
                || moved.getAmount() > roomFor(moved)) {
            return false;
        }
        int limit = limit(moved);
        int remaining = moved.getAmount();
        for (ItemStack stack : items) {
            if (remaining == 0) {
                break;
            }
            if (stack.isSimilar(moved) && stack.getAmount() < limit) {
                int added = Math.min(limit - stack.getAmount(), remaining);
                stack.setAmount(stack.getAmount() + added);
                remaining -= added;
            }
        }
        while (remaining > 0) {
            ItemStack stack = moved.clone();
            stack.setAmount(Math.min(limit, remaining));
            items.add(stack);
            remaining -= stack.getAmount();
        }
        return true;
    }

    /**
     * Takes {@code amount} items (or the whole stack, if it holds fewer) out of the stack at
     * {@code index}. The caller is responsible for the returned items.
     *
     * @return the items taken out, or null if there is no stack at {@code index}
     */
    ItemStack remove(int index, int amount) {
        if (index < 0 || index >= items.size() || amount <= 0) {
            return null;
        }
        ItemStack stack = items.get(index);
        if (amount >= stack.getAmount()) {
            return items.remove(index);
        }
        ItemStack taken = stack.clone();
        taken.setAmount(amount);
        stack.setAmount(stack.getAmount() - amount);
        return taken;
    }

    /** Empties the item list and returns what it held. The caller is responsible for the items. */
    List<ItemStack> takeAllItems() {
        List<ItemStack> taken = new ArrayList<>(items);
        items.clear();
        return taken;
    }

    /** @return false (and nothing changed) if {@code amount} is negative or the same as now */
    boolean setMoney(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(money) == 0) {
            return false;
        }
        money = amount;
        return true;
    }

    /** @return false (and nothing changed) if {@code levels} is negative or the same as now */
    boolean setXpLevels(int levels) {
        if (levels < 0 || levels == xpLevels) {
            return false;
        }
        xpLevels = levels;
        return true;
    }

    private static int limit(ItemStack item) {
        return Math.max(1, item.getMaxStackSize());
    }
}
