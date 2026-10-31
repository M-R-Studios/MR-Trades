package com.mrstudios.trades.trade;

/** A {@link StackView} made of plain values: stacks with the same key are similar. */
record PlainStack(String key, int amount, int maxStackSize) implements StackView {

    static PlainStack of(String key, int amount) {
        return new PlainStack(key, amount, 64);
    }

    static PlainStack pearls(int amount) {
        return new PlainStack("ender_pearl", amount, 16);
    }

    static PlainStack sword(String name) {
        return new PlainStack("sword:" + name, 1, 1);
    }

    @Override
    public boolean isSimilar(StackView other) {
        return other instanceof PlainStack p && p.key.equals(key);
    }
}
