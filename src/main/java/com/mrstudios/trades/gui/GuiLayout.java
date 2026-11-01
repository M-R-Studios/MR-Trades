package com.mrstudios.trades.gui;

/**
 * Slot positions of the trade window and the money/XP editor (DESIGN.md 3.3 and 3.3.1). The one
 * place they're defined; rendering and {@code ClickRules} both read them from here.
 *
 * <pre>
 * Trade window (54 slots), as each player sees it:
 * Row 0:  [0 your head] [1-3 filler] [4 status] [5-7 filler] [8 their head]
 * Row 1-4: [your offer x4] [divider] [their offer x4]
 * Row 5:  [45 money] [46 XP] [47 confirm] [48 cancel] [49 divider] [50 their confirm] [51 their XP] [52 their money] [53 filler]
 *
 * Editor (27 slots):
 * Row 0:  filler
 * Row 1:  [9 -big] [10 -] [11 -] [12 -small] [13 current] [14 +small] [15 +] [16 +] [17 +big]
 * Row 2:  [18 back] [20 type amount] [22 set to 0] [24 all/max] [26 done], filler between
 * </pre>
 */
public final class GuiLayout {

    public static final int TRADE_SIZE = 54;
    public static final int EDITOR_SIZE = 27;
    public static final int OFFER_SLOTS = 16;
    public static final int MAX_EDITOR_BUTTONS = 4;

    public static final int OWN_HEAD = 0;
    public static final int STATUS = 4;
    public static final int OTHER_HEAD = 8;
    public static final int MONEY = 45;
    public static final int XP = 46;
    public static final int CONFIRM = 47;
    public static final int CANCEL = 48;
    public static final int OTHER_CONFIRM = 50;
    public static final int OTHER_XP = 51;
    public static final int OTHER_MONEY = 52;

    public static final int EDITOR_CURRENT = 13;
    public static final int EDITOR_BACK = 18;
    public static final int EDITOR_TYPE = 20;
    public static final int EDITOR_ZERO = 22;
    public static final int EDITOR_MAX = 24;
    public static final int EDITOR_DONE = 26;

    /*
     * History (54 slots). List: 0-44 one trade each, newest first; row 5: [45 previous] [49 close] [53 next].
     * Details: what the viewed player gave in rows 0-3, columns 0-3, what they got in columns 5-8,
     * a divider in column 4, [36 gave money/XP] [44 got money/XP], row 5: [49 back].
     */
    public static final int HISTORY_SIZE = 54;
    public static final int HISTORY_PREVIOUS = 45;
    public static final int HISTORY_CLOSE = 49;
    public static final int HISTORY_NEXT = 53;
    public static final int DETAIL_GAVE_INFO = 36;
    public static final int DETAIL_GOT_INFO = 44;
    public static final int DETAIL_BACK = 49;

    private static final int FIRST_OFFER_ROW = 1;
    private static final int OFFER_ROWS = 4;
    private static final int OFFER_COLUMNS = 4;
    private static final int DIVIDER_COLUMN = 4;
    private static final int OTHER_OFFER_COLUMN = 5;

    /** What a slot of the trade window is. */
    public enum TradeSlot {
        OWN_HEAD, OTHER_HEAD, STATUS, OWN_OFFER, OTHER_OFFER, MONEY, XP, CONFIRM, CANCEL,
        OTHER_CONFIRM, OTHER_XP, OTHER_MONEY, DIVIDER, FILLER
    }

    /** What a slot of the editor is. */
    public enum EditorSlot {
        MINUS, PLUS, CURRENT, BACK, TYPE, ZERO, MAX, DONE, FILLER
    }

    private GuiLayout() {
    }

    /** The slot showing offer stack {@code index} (0-15) on your own (left) side. */
    public static int ownOfferSlot(int index) {
        checkOfferIndex(index);
        return (FIRST_OFFER_ROW + index / OFFER_COLUMNS) * 9 + index % OFFER_COLUMNS;
    }

    /** The slot showing the other player's offer stack {@code index} (0-15) on the right side. */
    public static int otherOfferSlot(int index) {
        return ownOfferSlot(index) + OTHER_OFFER_COLUMN;
    }

    /** The details slot of stack {@code index} (0-15): {@code gave} on the left, otherwise the right. */
    public static int detailSlot(boolean gave, int index) {
        checkOfferIndex(index);
        return (index / OFFER_COLUMNS) * 9 + index % OFFER_COLUMNS + (gave ? 0 : OTHER_OFFER_COLUMN);
    }

    /** The offer index shown in {@code slot} on your own side, or -1. */
    public static int ownOfferIndex(int slot) {
        return offerIndex(slot, 0);
    }

    /** The offer index shown in {@code slot} on the other player's side, or -1. */
    public static int otherOfferIndex(int slot) {
        return offerIndex(slot, OTHER_OFFER_COLUMN);
    }

    public static TradeSlot tradeSlot(int slot) {
        if (slot < 0 || slot >= TRADE_SIZE) {
            throw new IllegalArgumentException("Not a trade window slot: " + slot);
        }
        if (ownOfferIndex(slot) >= 0) {
            return TradeSlot.OWN_OFFER;
        }
        if (otherOfferIndex(slot) >= 0) {
            return TradeSlot.OTHER_OFFER;
        }
        return switch (slot) {
            case OWN_HEAD -> TradeSlot.OWN_HEAD;
            case STATUS -> TradeSlot.STATUS;
            case OTHER_HEAD -> TradeSlot.OTHER_HEAD;
            case MONEY -> TradeSlot.MONEY;
            case XP -> TradeSlot.XP;
            case CONFIRM -> TradeSlot.CONFIRM;
            case CANCEL -> TradeSlot.CANCEL;
            case OTHER_CONFIRM -> TradeSlot.OTHER_CONFIRM;
            case OTHER_XP -> TradeSlot.OTHER_XP;
            case OTHER_MONEY -> TradeSlot.OTHER_MONEY;
            default -> slot % 9 == DIVIDER_COLUMN && slot >= 9 ? TradeSlot.DIVIDER : TradeSlot.FILLER;
        };
    }

    /** The slot of the "-" button for {@code buttons[index]} (index 0 = smallest, nearest the middle). */
    public static int minusSlot(int index) {
        checkButtonIndex(index);
        return EDITOR_CURRENT - 1 - index;
    }

    /** The slot of the "+" button for {@code buttons[index]} (index 0 = smallest, nearest the middle). */
    public static int plusSlot(int index) {
        checkButtonIndex(index);
        return EDITOR_CURRENT + 1 + index;
    }

    /**
     * @param buttonCount how many amounts are configured (1-4); the unused +/- slots are filler
     */
    public static EditorSlot editorSlot(int slot, int buttonCount) {
        if (slot < 0 || slot >= EDITOR_SIZE) {
            throw new IllegalArgumentException("Not an editor slot: " + slot);
        }
        int index = editorButtonIndex(slot);
        if (index >= 0) {
            if (index >= buttonCount) {
                return EditorSlot.FILLER;
            }
            return slot < EDITOR_CURRENT ? EditorSlot.MINUS : EditorSlot.PLUS;
        }
        return switch (slot) {
            case EDITOR_CURRENT -> EditorSlot.CURRENT;
            case EDITOR_BACK -> EditorSlot.BACK;
            case EDITOR_TYPE -> EditorSlot.TYPE;
            case EDITOR_ZERO -> EditorSlot.ZERO;
            case EDITOR_MAX -> EditorSlot.MAX;
            case EDITOR_DONE -> EditorSlot.DONE;
            default -> EditorSlot.FILLER;
        };
    }

    /** For a +/- slot, the index into the configured amounts (0 = smallest); otherwise -1. */
    public static int editorButtonIndex(int slot) {
        if (slot >= EDITOR_CURRENT - MAX_EDITOR_BUTTONS && slot < EDITOR_CURRENT) {
            return EDITOR_CURRENT - 1 - slot;
        }
        if (slot > EDITOR_CURRENT && slot <= EDITOR_CURRENT + MAX_EDITOR_BUTTONS) {
            return slot - EDITOR_CURRENT - 1;
        }
        return -1;
    }

    private static int offerIndex(int slot, int firstColumn) {
        int row = slot / 9 - FIRST_OFFER_ROW;
        int column = slot % 9 - firstColumn;
        if (slot < 0 || row < 0 || row >= OFFER_ROWS || column < 0 || column >= OFFER_COLUMNS) {
            return -1;
        }
        return row * OFFER_COLUMNS + column;
    }

    private static void checkOfferIndex(int index) {
        if (index < 0 || index >= OFFER_SLOTS) {
            throw new IllegalArgumentException("Not an offer index: " + index);
        }
    }

    private static void checkButtonIndex(int index) {
        if (index < 0 || index >= MAX_EDITOR_BUTTONS) {
            throw new IllegalArgumentException("Not a button index: " + index);
        }
    }
}
