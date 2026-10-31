package com.mrstudios.trades.trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/** A real item as a {@link StackView}. Similarity is {@link ItemStack#isSimilar}, as in {@code addItem}. */
public record ItemStackView(ItemStack item) implements StackView {

    /** Views of {@code items}; null and air entries become null (an empty slot). */
    public static List<ItemStackView> of(Collection<ItemStack> items) {
        List<ItemStackView> views = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            views.add(item == null || item.getType().isAir() ? null : new ItemStackView(item));
        }
        return views;
    }

    public static List<ItemStackView> of(ItemStack[] items) {
        return of(Arrays.asList(items));
    }

    @Override
    public int amount() {
        return item.getAmount();
    }

    @Override
    public int maxStackSize() {
        return item.getMaxStackSize();
    }

    @Override
    public boolean isSimilar(StackView other) {
        return other instanceof ItemStackView view && item.isSimilar(view.item);
    }
}
