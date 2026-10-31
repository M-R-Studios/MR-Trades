package com.mrstudios.trades.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.gui.GuiLayout.EditorSlot;
import com.mrstudios.trades.gui.GuiLayout.TradeSlot;
import com.mrstudios.trades.rules.ClickAction.AddFromInventory;
import com.mrstudios.trades.rules.ClickAction.Button;
import com.mrstudios.trades.rules.ClickAction.Press;
import com.mrstudios.trades.rules.ClickAction.ReturnFromOffer;
import com.mrstudios.trades.rules.ClickSnapshot.View;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Table-driven checks of DESIGN.md 3.5 #1-#5 over every slot and click type. */
class ClickRulesTest {

    private static final int TRADE = GuiLayout.TRADE_SIZE;
    private static final int EDITOR = GuiLayout.EDITOR_SIZE;
    private static final int ALL_SLOTS_TRADE = TRADE + 36;
    private static final int ALL_SLOTS_EDITOR = EDITOR + 36;

    /** The click types that may ever do something; everything else must always be NONE. */
    private static final Set<ClickType> ACTIVE =
            EnumSet.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT, ClickType.SHIFT_RIGHT);

    private static ClickAction trade(ClickType click, int raw) {
        return ClickRules.decide(new ClickSnapshot(View.TRADE, click, raw, TRADE, true, true, true, true, 4));
    }

    private static ClickAction editor(ClickType click, int raw, int buttons) {
        return ClickRules.decide(new ClickSnapshot(View.EDITOR, click, raw, EDITOR, true, true, true, true, buttons));
    }

    // --- Layout ---

    @Test
    void layoutMatchesTheDesign() {
        assertEquals(TradeSlot.OWN_HEAD, GuiLayout.tradeSlot(0));
        assertEquals(TradeSlot.STATUS, GuiLayout.tradeSlot(4));
        assertEquals(TradeSlot.OTHER_HEAD, GuiLayout.tradeSlot(8));
        for (int slot : new int[] {1, 2, 3, 5, 6, 7, 53}) {
            assertEquals(TradeSlot.FILLER, GuiLayout.tradeSlot(slot), "slot " + slot);
        }
        for (int slot : new int[] {13, 22, 31, 40, 49}) {
            assertEquals(TradeSlot.DIVIDER, GuiLayout.tradeSlot(slot), "slot " + slot);
        }
        assertEquals(TradeSlot.MONEY, GuiLayout.tradeSlot(45));
        assertEquals(TradeSlot.XP, GuiLayout.tradeSlot(46));
        assertEquals(TradeSlot.CONFIRM, GuiLayout.tradeSlot(47));
        assertEquals(TradeSlot.CANCEL, GuiLayout.tradeSlot(48));
        assertEquals(TradeSlot.OTHER_CONFIRM, GuiLayout.tradeSlot(50));
        assertEquals(TradeSlot.OTHER_XP, GuiLayout.tradeSlot(51));
        assertEquals(TradeSlot.OTHER_MONEY, GuiLayout.tradeSlot(52));
    }

    @Test
    void offerSlotsAreSixteenPerSideAndRoundTrip() {
        Set<Integer> own = new HashSet<>();
        Set<Integer> other = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            int slot = GuiLayout.ownOfferSlot(i);
            assertEquals(i, GuiLayout.ownOfferIndex(slot));
            assertEquals(TradeSlot.OWN_OFFER, GuiLayout.tradeSlot(slot));
            assertEquals(i, GuiLayout.otherOfferIndex(GuiLayout.otherOfferSlot(i)));
            assertEquals(TradeSlot.OTHER_OFFER, GuiLayout.tradeSlot(GuiLayout.otherOfferSlot(i)));
            own.add(slot);
            other.add(GuiLayout.otherOfferSlot(i));
        }
        assertEquals(Set.of(9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39), own);
        assertEquals(Set.of(14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44), other);
        assertEquals(-1, GuiLayout.ownOfferIndex(13));
        assertEquals(-1, GuiLayout.ownOfferIndex(45));
        assertEquals(-1, GuiLayout.otherOfferIndex(12));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.ownOfferSlot(16));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.tradeSlot(54));
    }

    @Test
    void editorLayoutMatchesTheDesign() {
        assertEquals(12, GuiLayout.minusSlot(0));
        assertEquals(9, GuiLayout.minusSlot(3));
        assertEquals(14, GuiLayout.plusSlot(0));
        assertEquals(17, GuiLayout.plusSlot(3));
        assertEquals(EditorSlot.CURRENT, GuiLayout.editorSlot(13, 4));
        assertEquals(EditorSlot.BACK, GuiLayout.editorSlot(18, 4));
        assertEquals(EditorSlot.TYPE, GuiLayout.editorSlot(20, 4));
        assertEquals(EditorSlot.ZERO, GuiLayout.editorSlot(22, 4));
        assertEquals(EditorSlot.MAX, GuiLayout.editorSlot(24, 4));
        assertEquals(EditorSlot.DONE, GuiLayout.editorSlot(26, 4));
        for (int slot : new int[] {0, 4, 8, 19, 21, 23, 25}) {
            assertEquals(EditorSlot.FILLER, GuiLayout.editorSlot(slot, 4), "slot " + slot);
        }
        // With two amounts, only the two buttons nearest the middle exist on each side.
        assertEquals(EditorSlot.MINUS, GuiLayout.editorSlot(11, 2));
        assertEquals(EditorSlot.FILLER, GuiLayout.editorSlot(10, 2));
        assertEquals(EditorSlot.PLUS, GuiLayout.editorSlot(15, 2));
        assertEquals(EditorSlot.FILLER, GuiLayout.editorSlot(16, 2));
    }

    // --- Trade window ---

    @ParameterizedTest
    @CsvSource({
        // raw slot, player slot: the 27 main slots come first, then the hotbar
        "54, 9", "62, 17", "80, 35", "81, 0", "89, 8"})
    void bottomInventoryLeftAndShiftLeftAddWholeStackRightAddsOne(int raw, int playerSlot) {
        assertEquals(new AddFromInventory(playerSlot, true), trade(ClickType.LEFT, raw));
        assertEquals(new AddFromInventory(playerSlot, true), trade(ClickType.SHIFT_LEFT, raw));
        assertEquals(new AddFromInventory(playerSlot, false), trade(ClickType.RIGHT, raw));
        assertEquals(ClickAction.NONE, trade(ClickType.SHIFT_RIGHT, raw));
    }

    @Test
    void ownOfferSlotsReturnItems() {
        for (int i = 0; i < 16; i++) {
            int slot = GuiLayout.ownOfferSlot(i);
            assertEquals(new ReturnFromOffer(i, true), trade(ClickType.LEFT, slot));
            assertEquals(new ReturnFromOffer(i, true), trade(ClickType.SHIFT_LEFT, slot));
            assertEquals(new ReturnFromOffer(i, false), trade(ClickType.RIGHT, slot));
            assertEquals(ClickAction.NONE, trade(ClickType.SHIFT_RIGHT, slot));
        }
    }

    @Test
    void buttonsInTheTradeWindow() {
        for (ClickType click : ACTIVE) {
            assertEquals(new Press(Button.MONEY, 0), trade(click, GuiLayout.MONEY));
            assertEquals(new Press(Button.XP, 0), trade(click, GuiLayout.XP));
            assertEquals(new Press(Button.CONFIRM, 0), trade(click, GuiLayout.CONFIRM));
            assertEquals(new Press(Button.CANCEL, 0), trade(click, GuiLayout.CANCEL));
        }
    }

    @Test
    void otherSideFillersDividersHeadsAndStatusDoNothing() {
        for (int slot = 0; slot < TRADE; slot++) {
            TradeSlot kind = GuiLayout.tradeSlot(slot);
            if (Set.of(TradeSlot.OWN_OFFER, TradeSlot.MONEY, TradeSlot.XP, TradeSlot.CONFIRM, TradeSlot.CANCEL)
                    .contains(kind)) {
                continue;
            }
            for (ClickType click : ClickType.values()) {
                assertEquals(ClickAction.NONE, trade(click, slot), kind + " slot " + slot + " " + click);
            }
        }
    }

    @Test
    void hiddenMoneyAndXpButtonsDoNothing() {
        ClickSnapshot money = new ClickSnapshot(View.TRADE, ClickType.LEFT, GuiLayout.MONEY, TRADE, true, true,
                false, true, 4);
        ClickSnapshot xp = new ClickSnapshot(View.TRADE, ClickType.LEFT, GuiLayout.XP, TRADE, true, true, true,
                false, 4);
        assertEquals(ClickAction.NONE, ClickRules.decide(money));
        assertEquals(ClickAction.NONE, ClickRules.decide(xp));
    }

    /** Number keys, offhand swap, drops, double-click, middle, creative, borders: never anything, anywhere. */
    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"LEFT", "SHIFT_LEFT", "RIGHT", "SHIFT_RIGHT"},
            mode = EnumSource.Mode.EXCLUDE)
    void everyOtherClickTypeDoesNothingOnEverySlot(ClickType click) {
        for (int raw = 0; raw < ALL_SLOTS_TRADE; raw++) {
            assertEquals(ClickAction.NONE, trade(click, raw), click + " on raw slot " + raw);
        }
        for (int raw = 0; raw < ALL_SLOTS_EDITOR; raw++) {
            assertEquals(ClickAction.NONE, editor(click, raw, 4), click + " on editor raw slot " + raw);
        }
    }

    @Test
    void theDangerousClickTypesAreAllCovered() {
        for (ClickType click : new ClickType[] {ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DROP,
            ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.MIDDLE, ClickType.CREATIVE,
            ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT, ClickType.UNKNOWN}) {
            assertEquals(ClickAction.NONE, trade(click, GuiLayout.ownOfferSlot(0)), click.name());
            assertEquals(ClickAction.NONE, trade(click, GuiLayout.otherOfferSlot(0)), click.name());
            assertEquals(ClickAction.NONE, trade(click, TRADE + 5), click.name());
            assertEquals(ClickAction.NONE, trade(click, GuiLayout.CONFIRM), click.name());
        }
    }

    @Test
    void nonOwnerAndEndedSessionDoNothing() {
        for (int raw = 0; raw < ALL_SLOTS_TRADE; raw++) {
            for (ClickType click : ACTIVE) {
                assertEquals(ClickAction.NONE, ClickRules.decide(
                        new ClickSnapshot(View.TRADE, click, raw, TRADE, false, true, true, true, 4)));
                assertEquals(ClickAction.NONE, ClickRules.decide(
                        new ClickSnapshot(View.TRADE, click, raw, TRADE, true, false, true, true, 4)));
            }
        }
    }

    @Test
    void outsideTheWindowAndWrongSizesDoNothing() {
        assertEquals(ClickAction.NONE, trade(ClickType.LEFT, -999));
        assertEquals(ClickAction.NONE, trade(ClickType.LEFT, -1));
        assertEquals(ClickAction.NONE, trade(ClickType.LEFT, ALL_SLOTS_TRADE));
        assertEquals(ClickAction.NONE, ClickRules.decide(
                new ClickSnapshot(View.TRADE, ClickType.LEFT, 9, 27, true, true, true, true, 4)));
        assertEquals(ClickAction.NONE, ClickRules.decide(
                new ClickSnapshot(View.EDITOR, ClickType.LEFT, 13, 54, true, true, true, true, 4)));
        assertEquals(ClickAction.NONE, ClickRules.decide(
                new ClickSnapshot(View.TRADE, null, 9, TRADE, true, true, true, true, 4)));
    }

    // --- Editor ---

    @Test
    void editorButtons() {
        for (ClickType click : ACTIVE) {
            for (int i = 0; i < 4; i++) {
                assertEquals(new Press(Button.EDITOR_MINUS, i), editor(click, GuiLayout.minusSlot(i), 4));
                assertEquals(new Press(Button.EDITOR_PLUS, i), editor(click, GuiLayout.plusSlot(i), 4));
            }
            assertEquals(new Press(Button.EDITOR_BACK, 0), editor(click, 18, 4));
            assertEquals(new Press(Button.EDITOR_TYPE, 0), editor(click, 20, 4));
            assertEquals(new Press(Button.EDITOR_ZERO, 0), editor(click, 22, 4));
            assertEquals(new Press(Button.EDITOR_MAX, 0), editor(click, 24, 4));
            assertEquals(new Press(Button.EDITOR_DONE, 0), editor(click, 26, 4));
            assertEquals(ClickAction.NONE, editor(click, 13, 4), "the current amount isn't a button");
        }
    }

    @Test
    void unusedAmountButtonsDoNothing() {
        assertEquals(new Press(Button.EDITOR_MINUS, 0), editor(ClickType.LEFT, 12, 1));
        assertEquals(ClickAction.NONE, editor(ClickType.LEFT, 11, 1));
        assertEquals(ClickAction.NONE, editor(ClickType.LEFT, 9, 1));
        assertEquals(new Press(Button.EDITOR_PLUS, 0), editor(ClickType.LEFT, 14, 1));
        assertEquals(ClickAction.NONE, editor(ClickType.LEFT, 17, 3));
        assertEquals(new Press(Button.EDITOR_PLUS, 2), editor(ClickType.LEFT, 16, 3));
    }

    @Test
    void nothingMovesWhileTheEditorIsOpen() {
        for (int raw = EDITOR; raw < ALL_SLOTS_EDITOR; raw++) {
            for (ClickType click : ClickType.values()) {
                assertEquals(ClickAction.NONE, editor(click, raw, 4), click + " on " + raw);
            }
        }
    }
}
