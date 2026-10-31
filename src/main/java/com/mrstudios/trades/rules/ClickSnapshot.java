package com.mrstudios.trades.rules;

import org.bukkit.event.inventory.ClickType;

/**
 * The facts about one click that {@link ClickRules} decides on, copied out of the event.
 *
 * @param view which of our inventories the click happened in
 * @param click the click type
 * @param rawSlot the raw slot of the view: top inventory first, then the player's inventory
 *     (main 27 slots, then the hotbar); -999 or below 0 for outside the window
 * @param topSize the size of the top inventory
 * @param owner the clicker is the player this view belongs to
 * @param live the trade hasn't ended
 * @param moneyShown the money button is shown (money on, Vault present, permission)
 * @param xpShown the XP button is shown
 * @param editorButtons how many +/- amounts the open editor has (1-4); ignored in the trade window
 */
public record ClickSnapshot(View view, ClickType click, int rawSlot, int topSize, boolean owner, boolean live,
        boolean moneyShown, boolean xpShown, int editorButtons) {

    public enum View {
        TRADE,
        EDITOR
    }
}
