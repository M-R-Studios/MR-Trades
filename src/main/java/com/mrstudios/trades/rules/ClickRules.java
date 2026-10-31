package com.mrstudios.trades.rules;

import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.rules.ClickAction.AddFromInventory;
import com.mrstudios.trades.rules.ClickAction.Button;
import com.mrstudios.trades.rules.ClickAction.Press;
import com.mrstudios.trades.rules.ClickAction.ReturnFromOffer;
import com.mrstudios.trades.rules.ClickSnapshot.View;
import org.bukkit.event.inventory.ClickType;

/**
 * Decides what a click in our views does (DESIGN.md 3.5 #1-#5). The listener always cancels the
 * event and then performs only the action returned here, so vanilla click mechanics (cursor,
 * number keys, offhand swap, drops, double-click collect, middle-click clone, drags) never touch
 * a trade. Anything not explicitly allowed is {@link ClickAction#NONE}.
 *
 * <p>Allowed: {@code LEFT}/{@code SHIFT_LEFT} move a whole stack and {@code RIGHT} moves one item,
 * between your own inventory and your own offer; {@code LEFT}, {@code SHIFT_LEFT}, {@code RIGHT}
 * and {@code SHIFT_RIGHT} press buttons.
 */
public final class ClickRules {

    /** Player inventory slots shown below a chest view: 27 main slots, then the 9 hotbar slots. */
    private static final int MAIN_SLOTS = 27;
    private static final int PLAYER_SLOTS = 36;
    private static final int FIRST_MAIN_SLOT = 9;

    private ClickRules() {
    }

    public static ClickAction decide(ClickSnapshot c) {
        if (!c.owner() || !c.live() || c.click() == null) {
            return ClickAction.NONE;
        }
        int expectedSize = c.view() == View.TRADE ? GuiLayout.TRADE_SIZE : GuiLayout.EDITOR_SIZE;
        if (c.topSize() != expectedSize || c.rawSlot() < 0 || c.rawSlot() >= expectedSize + PLAYER_SLOTS) {
            return ClickAction.NONE;
        }
        return c.view() == View.TRADE ? trade(c) : editor(c);
    }

    /** The player inventory slot behind a raw slot of the bottom inventory. */
    static int playerSlot(int rawSlot, int topSize) {
        int bottom = rawSlot - topSize;
        return bottom < MAIN_SLOTS ? bottom + FIRST_MAIN_SLOT : bottom - MAIN_SLOTS;
    }

    private static ClickAction trade(ClickSnapshot c) {
        int raw = c.rawSlot();
        if (raw >= c.topSize()) {
            return isItemMove(c.click())
                    ? new AddFromInventory(playerSlot(raw, c.topSize()), isWholeStack(c.click()))
                    : ClickAction.NONE;
        }
        return switch (GuiLayout.tradeSlot(raw)) {
            case OWN_OFFER -> isItemMove(c.click())
                    ? new ReturnFromOffer(GuiLayout.ownOfferIndex(raw), isWholeStack(c.click()))
                    : ClickAction.NONE;
            case MONEY -> c.moneyShown() ? button(c, Button.MONEY, 0) : ClickAction.NONE;
            case XP -> c.xpShown() ? button(c, Button.XP, 0) : ClickAction.NONE;
            case CONFIRM -> button(c, Button.CONFIRM, 0);
            case CANCEL -> button(c, Button.CANCEL, 0);
            default -> ClickAction.NONE;
        };
    }

    private static ClickAction editor(ClickSnapshot c) {
        int raw = c.rawSlot();
        if (raw >= c.topSize()) {
            // Nothing moves in or out of an offer while an amount is being edited.
            return ClickAction.NONE;
        }
        int buttons = Math.max(0, Math.min(GuiLayout.MAX_EDITOR_BUTTONS, c.editorButtons()));
        return switch (GuiLayout.editorSlot(raw, buttons)) {
            case MINUS -> button(c, Button.EDITOR_MINUS, GuiLayout.editorButtonIndex(raw));
            case PLUS -> button(c, Button.EDITOR_PLUS, GuiLayout.editorButtonIndex(raw));
            case TYPE -> button(c, Button.EDITOR_TYPE, 0);
            case ZERO -> button(c, Button.EDITOR_ZERO, 0);
            case MAX -> button(c, Button.EDITOR_MAX, 0);
            case DONE -> button(c, Button.EDITOR_DONE, 0);
            case BACK -> button(c, Button.EDITOR_BACK, 0);
            case CURRENT, FILLER -> ClickAction.NONE;
        };
    }

    private static ClickAction button(ClickSnapshot c, Button button, int index) {
        return switch (c.click()) {
            case LEFT, SHIFT_LEFT, RIGHT, SHIFT_RIGHT -> new Press(button, index);
            default -> ClickAction.NONE;
        };
    }

    private static boolean isItemMove(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.SHIFT_LEFT || click == ClickType.RIGHT;
    }

    private static boolean isWholeStack(ClickType click) {
        return click != ClickType.RIGHT;
    }
}
