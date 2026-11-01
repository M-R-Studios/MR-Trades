package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.GuiLayout;
import java.math.BigDecimal;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Quick double taps on buttons that switch windows (found on a real Bedrock client: tapping Back
 * twice ended the trade). Both taps arrive before the switch, which runs on the next tick.
 */
class EditorDoubleTapTest extends TradeFixture {

    @BeforeEach
    void setUpEconomy() throws Exception {
        FakeEconomy economy = new FakeEconomy();
        economy.balances.put(alex.getUniqueId(), new BigDecimal("500"));
        plugin.hooks().overrideEconomy(economy);
        setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
        alex.setLevel(20);
    }

    private EditorHolder openEditor(int button) {
        click(alex, ClickType.LEFT, button);
        server.getScheduler().performTicks(1);
        EditorHolder editor = EditorHolder.of(alex.getOpenInventory().getTopInventory());
        assertNotNull(editor);
        return editor;
    }

    private void assertStillTrading(TradeSession session) {
        server.getScheduler().performTicks(3);
        assertFalse(session.isEnded(), "a double tap must never end the trade");
        assertTrue(isTradeWindow(alex), "back in the trade window");
        assertEquals(ViewState.TRADE, session.a().view());
        server.getScheduler().performTicks(40);
        assertFalse(session.isEnded(), "and the watchdog agrees");
    }

    @ParameterizedTest(name = "editor button {0}")
    @ValueSource(ints = {GuiLayout.MONEY, GuiLayout.XP})
    void backTappedTwice(int button) {
        TradeSession session = start();
        openEditor(button);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        assertStillTrading(session);
    }

    @Test
    void doneTappedTwiceAppliesOnce() {
        TradeSession session = start();
        openEditor(GuiLayout.XP);
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(1));
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        assertStillTrading(session);
        assertEquals(5, session.a().offer().xpLevels());
        assertEquals(1, session.version(), "one change, not two");
    }

    @Test
    void doneThenBackKeepsTheDone() {
        TradeSession session = start();
        openEditor(GuiLayout.MONEY);
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(1));
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        assertStillTrading(session);
        assertEquals(0, BigDecimal.TEN.compareTo(session.a().offer().money()));
    }

    @Test
    void plusAfterBackDoesNotChangeAnything() {
        TradeSession session = start();
        EditorHolder editor = openEditor(GuiLayout.XP);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(3));
        assertEquals(0, editor.draft().signum());
        assertStillTrading(session);
        assertEquals(0, session.a().offer().xpLevels());
    }

    @ParameterizedTest(name = "editor button {0}")
    @ValueSource(ints = {GuiLayout.MONEY, GuiLayout.XP})
    void editorButtonTappedTwiceOpensOneEditor(int button) {
        TradeSession session = start();
        click(alex, ClickType.LEFT, button);
        click(alex, ClickType.LEFT, button);
        server.getScheduler().performTicks(3);

        assertFalse(session.isEnded());
        assertNotNull(EditorHolder.of(alex.getOpenInventory().getTopInventory()), "one editor, still open");
        assertEquals(ViewState.EDITOR, session.a().view());
        server.getScheduler().performTicks(40);
        assertFalse(session.isEnded());
    }

    @Test
    void moneyThenXpTappedQuicklyOpensOnlyTheFirst() {
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        click(alex, ClickType.LEFT, GuiLayout.XP);
        server.getScheduler().performTicks(3);
        assertFalse(session.isEnded());
        assertEquals(EditorHolder.Kind.MONEY, EditorHolder.of(alex.getOpenInventory().getTopInventory()).kind());
    }

    @Test
    void typeTappedTwiceStartsOneWait() {
        TradeSession session = start();
        openEditor(GuiLayout.MONEY);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_TYPE);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_TYPE);
        server.getScheduler().performTicks(3);
        assertFalse(session.isEnded());
        assertEquals(ViewState.INPUT_CHAT, session.a().view());
        assertFalse(isTradeWindow(alex));
    }

    @Test
    void escThenBackInTheSameTick() {
        TradeSession session = start();
        openEditor(GuiLayout.XP);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        alex.closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLAYER);
        assertStillTrading(session);
    }
}
