package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.GuiLayout;
import java.math.BigDecimal;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Window switches for Bedrock players. Found on a real Bedrock client: when the server swaps one
 * window for another, the client's late "closed" for the old screen closes the new one, which
 * ended the trade after "Back" in the editor. Bedrock switches now close, pause, then open, and a
 * close right after the plugin opened a window is taken as stray.
 */
class BedrockWindowSwitchTest extends TradeFixture {

    private AmountInputTest.FakeForms forms;

    @BeforeEach
    void setUpBedrock() throws Exception {
        FakeEconomy economy = new FakeEconomy();
        economy.balances.put(alex.getUniqueId(), new BigDecimal("500"));
        plugin.hooks().overrideEconomy(economy);
        forms = new AmountInputTest.FakeForms();
        forms.bedrock.add(alex.getUniqueId());
        plugin.hooks().overrideBedrock(forms);
        setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
        alex.setLevel(20);
    }

    private EditorHolder editor() {
        return EditorHolder.of(alex.getOpenInventory().getTopInventory());
    }

    private void openMoneyEditor() {
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertNotNull(editor(), "the editor is open after the pause");
    }

    @Test
    void bedrockSwitchClosesFirstThenOpensAfterAPause() {
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        assertTrue(isTradeWindow(alex), "never closed inside the click event itself");
        assertEquals(ViewState.REOPENING, session.a().view());

        server.getScheduler().performTicks(1);
        assertFalse(isTradeWindow(alex), "the trade window closes on the next tick");
        assertNull(editor());
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS - 1);
        assertNull(editor(), "nothing opens during the pause");

        server.getScheduler().performTicks(2);
        assertNotNull(editor());
        assertEquals(ViewState.EDITOR, session.a().view());
        assertFalse(session.isEnded());
    }

    @Test
    void lateCloseDuringThePauseHitsNothing() {
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(1);
        // The Bedrock client's "closed" for the trade screen arrives during the pause.
        alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertFalse(session.isEnded());
        assertNotNull(editor());
    }

    @Test
    void backFromTheEditorThenAStrayCloseKeepsTheTrade() {
        TradeSession session = start();
        openMoneyEditor();
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertTrue(isTradeWindow(alex));

        // The reported bug: the client's late close lands on the new trade window.
        alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);

        assertFalse(session.isEnded(), "a stray close must not end the trade");
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertTrue(isTradeWindow(alex), "the trade window comes back");
        assertEquals(ViewState.TRADE, session.a().view());
        server.getScheduler().performTicks(40);
        assertFalse(session.isEnded());
    }

    @Test
    void aRealCloseLaterStillCancels() {
        TradeSession session = start();
        server.getScheduler().performTicks(TradeService.STRAY_CLOSE_TICKS + 2);
        alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        assertTrue(session.isEnded(), "Esc / X a moment after the window opened cancels as always");
    }

    @Test
    void aClientThatKeepsClosingGetsOutEventually() {
        TradeSession session = start();
        for (int i = 0; i < 5 && !session.isEnded(); i++) {
            alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);
            server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        }
        assertTrue(session.isEnded(), "after a couple of absorbed closes, a close is real");
    }

    @Test
    void javaPlayersAreUnchanged() {
        alex.closeInventory();
        forms.bedrock.clear();
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(1);
        assertNotNull(editor(), "Java switches next tick, no pause");
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex));

        alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        assertTrue(session.isEnded(), "no stray-close leniency for Java: Esc cancels at once");
    }

    @Test
    void bedrockDoneAppliesAndComesBack() {
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.XP);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(1));
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertEquals(5, session.a().offer().xpLevels());
        assertTrue(isTradeWindow(alex));
        assertFalse(session.isEnded());
    }

    // --- Stray taps into the world (a placed shulker box opened over the trade) ---

    private org.bukkit.event.player.PlayerInteractEvent rightClickChest() {
        org.bukkit.block.Block chest = world.getBlockAt(1, 64, 1);
        chest.setType(org.bukkit.Material.SHULKER_BOX);
        org.bukkit.event.player.PlayerInteractEvent event = new org.bukkit.event.player.PlayerInteractEvent(alex,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, chest, org.bukkit.block.BlockFace.UP);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void rightClickingABlockWhileTradingOpensNothing() {
        start();
        org.bukkit.event.player.PlayerInteractEvent event = rightClickChest();
        assertEquals(org.bukkit.event.Event.Result.DENY, event.useInteractedBlock());
        assertEquals(org.bukkit.event.Event.Result.DENY, event.useItemInHand());
    }

    @Test
    void rightClickingABlockOutsideATradeIsUntouched() {
        org.bukkit.event.player.PlayerInteractEvent event = rightClickChest();
        assertTrue(event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY);
    }

    @Test
    void rightClickingAnEntityWhileTradingIsCancelled() {
        start();
        org.bukkit.event.player.PlayerInteractEntityEvent event =
                new org.bukkit.event.player.PlayerInteractEntityEvent(alex, sam);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void anotherWindowOpenedOverTheTradeStillEndsIt() {
        // A plugin (or anything else) really opening its own window: by design the trade ends and
        // everything goes back (DESIGN.md 3.5 #8). Only world taps are blocked.
        TradeSession session = start();
        alex.openInventory(server.createInventory(null, 27));
        assertTrue(session.isEnded());
    }
}
