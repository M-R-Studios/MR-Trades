package com.mrstudios.trades.trade;

/**
 * What {@link InventorySimulator} needs to know about a stack. Lets the simulator be tested with
 * plain values as well as real items ({@link ItemStackView}).
 */
public interface StackView {

    int amount();

    /** The item's own limit (its {@code max_stack_size}), before the inventory's limit is applied. */
    int maxStackSize();

    /** Whether the two stacks can merge: same item, same data, everything but the amount. */
    boolean isSimilar(StackView other);
}
