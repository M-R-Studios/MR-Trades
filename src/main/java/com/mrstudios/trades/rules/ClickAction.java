package com.mrstudios.trades.rules;

/** What a click in a trade window or editor should do, decided by {@link ClickRules}. */
public sealed interface ClickAction {

    /** Do nothing. The click is cancelled either way. */
    record None() implements ClickAction {
    }

    /**
     * Move items from the clicker's own inventory into their offer.
     *
     * @param playerSlot the slot in the player's inventory (0-8 hotbar, 9-35 main)
     * @param wholeStack the whole stack, or just one item
     */
    record AddFromInventory(int playerSlot, boolean wholeStack) implements ClickAction {
    }

    /**
     * Move items from the clicker's offer back into their inventory.
     *
     * @param offerIndex the offer stack (0-15)
     * @param wholeStack the whole stack, or just one item
     */
    record ReturnFromOffer(int offerIndex, boolean wholeStack) implements ClickAction {
    }

    /**
     * Press a button.
     *
     * @param index for {@link Button#EDITOR_MINUS}/{@link Button#EDITOR_PLUS}, which configured
     *     amount (0 = smallest); otherwise 0
     */
    record Press(Button button, int index) implements ClickAction {
    }

    ClickAction NONE = new None();

    enum Button {
        MONEY, XP, CONFIRM, CANCEL,
        EDITOR_MINUS, EDITOR_PLUS, EDITOR_TYPE, EDITOR_ZERO, EDITOR_MAX, EDITOR_DONE, EDITOR_BACK
    }
}
