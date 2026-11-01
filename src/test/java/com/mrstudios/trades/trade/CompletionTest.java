package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.GuiLayout;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Confirm, countdown and atomic completion (DESIGN.md 3.4, 3.5.1, 8.1 CompletionTest). */
class CompletionTest extends TradeFixture {

    private static final int COUNTDOWN_TICKS = 3 * 20;

    /**
     * Alex offers 64 diamonds, Excalibur and a full shulker; Sam offers 16 pearls. The change lock
     * runs on wall-clock time, which mock ticks don't advance, so it's off here (TradeSessionTest
     * covers it with a fake clock).
     */
    private TradeSession tradeWithItems() {
        try {
            setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        alex.getInventory().setItem(1, excalibur());
        alex.getInventory().setItem(2, shulkerOfDiamonds());
        sam.getInventory().setItem(4, new ItemStack(Material.ENDER_PEARL, 16));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(alex, ClickType.LEFT, raw(1));
        click(alex, ClickType.LEFT, raw(2));
        click(sam, ClickType.LEFT, raw(4));
        return session;
    }

    private void bothConfirm() {
        click(alex, ClickType.LEFT, GuiLayout.CONFIRM);
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return lines;
    }

    private void assertCompletedCleanly(TradeSession session) {
        assertTrue(session.isEnded());
        assertTrue(trades().registry().isEmpty());
        assertNull(plugin.returnsService().store().readEscrow(alex));
        assertNull(plugin.returnsService().store().readEscrow(sam));
        server.getScheduler().performTicks(1);
        assertFalse(isTradeWindow(alex));
        assertFalse(isTradeWindow(sam));
        for (PlayerMock player : List.of(alex, sam)) {
            for (ItemStack item : nonEmpty(player.getInventory().getContents())) {
                assertFalse(trades().display().isDisplay(item), "display copy left with " + player.getName());
            }
            assertTrue(player.getItemOnCursor() == null || player.getItemOnCursor().getType().isAir());
        }
    }

    @Test
    void itemsSwapExactlyAfterTheCountdown() {
        TradeSession session = tradeWithItems();
        drain(alex);
        drain(sam);
        bothConfirm();
        assertEquals(TradeState.COUNTDOWN, session.state());

        server.getScheduler().performTicks(COUNTDOWN_TICKS - 5);
        assertEquals(TradeState.COUNTDOWN, session.state(), "not before the countdown ends");
        server.getScheduler().performTicks(10);

        List<ItemStack> samItems = nonEmpty(sam.getInventory().getStorageContents());
        assertTrue(samItems.contains(new ItemStack(Material.DIAMOND, 64)), samItems.toString());
        assertTrue(samItems.contains(excalibur()));
        assertTrue(samItems.contains(shulkerOfDiamonds()));
        assertEquals(3, samItems.size());
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), nonEmpty(alex.getInventory().getStorageContents()));
        assertTrue(drain(alex).contains("MR-Trades » Trade with Sam complete."));
        assertTrue(drain(sam).contains("MR-Trades » Trade with Alex complete."));
        assertCompletedCleanly(session);
    }

    @Test
    void statusShowsTheSecondsLeft() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        assertEquals(3, alex.getOpenInventory().getTopInventory().getItem(GuiLayout.STATUS).getAmount());
        server.getScheduler().performTicks(20);
        assertEquals(2, session.countdownRemaining());
        assertEquals(2, alex.getOpenInventory().getTopInventory().getItem(GuiLayout.STATUS).getAmount());
    }

    @Test
    void zeroCountdownCompletesAtOnce() throws Exception {
        setConfig("countdown-seconds: 3", "countdown-seconds: 0");
        TradeSession session = tradeWithItems();
        bothConfirm();
        assertTrue(session.isEnded());
        assertEquals(16, count(alex, Material.ENDER_PEARL));
    }

    @Test
    void xpLevelsMove() {
        alex.setLevel(30);
        sam.setLevel(5);
        TradeSession session = start();
        session.setXpLevels(session.a(), 0, 10);
        session.setXpLevels(session.b(), 0, 3);
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertTrue(session.isEnded());
        assertEquals(23, alex.getLevel());
        assertEquals(12, sam.getLevel());
    }

    @Test
    void receiverWithoutSpaceKeepsTheTradeOpenAndConfirmingAgainWorks() {
        TradeSession session = tradeWithItems();
        for (int slot = 0; slot < 36; slot++) {
            if (sam.getInventory().getItem(slot) == null) {
                sam.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
            }
        }
        drain(alex);
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertEquals(TradeState.OPEN, session.state(), "decision 5: the trade stays open");
        assertFalse(session.bothConfirmed());
        assertEquals(3, session.a().offer().stackCount(), "nothing moved");
        assertEquals(1, session.b().offer().stackCount());
        assertEquals(0, count(alex, Material.ENDER_PEARL));
        assertTrue(drain(alex).contains("MR-Trades » Sam doesn't have enough inventory space. Confirm again when there's room."));
        assertTrue(isTradeWindow(alex));

        sam.getInventory().clear();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);
        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(sam.getInventory().getStorageContents()).size());
    }

    @Test
    void notEnoughLevelsAtCompletionKeepsItOpen() {
        alex.setLevel(30);
        TradeSession session = start();
        session.setXpLevels(session.a(), 0, 10);
        bothConfirm();
        alex.setLevel(4);
        drain(sam);
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertEquals(TradeState.OPEN, session.state());
        assertEquals(4, alex.getLevel());
        assertEquals(0, sam.getLevel());
        assertTrue(drain(sam).contains("MR-Trades » Alex doesn't have enough levels. Confirm again after changing the offer."));
    }

    @Test
    void changeDuringTheCountdownResetsBothAndStopsIt() {
        alex.getInventory().setItem(5, new ItemStack(Material.EMERALD, 3));
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(30);

        click(alex, ClickType.LEFT, raw(5));

        assertEquals(TradeState.OPEN, session.state());
        assertFalse(session.bothConfirmed());
        server.getScheduler().performTicks(COUNTDOWN_TICKS * 2);
        assertFalse(session.isEnded(), "the stopped countdown never completes");
        assertEquals(4, session.a().offer().stackCount());
    }

    @Test
    void removingAnItemOnTheLastTickStopsCompletion() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS - 1);

        click(alex, ClickType.LEFT, GuiLayout.ownOfferSlot(1)); // takes Excalibur back
        server.getScheduler().performTicks(5);

        assertFalse(session.isEnded());
        assertTrue(nonEmpty(alex.getInventory().getStorageContents()).contains(excalibur()));
        assertEquals(0, count(sam, Material.DIAMOND));
    }

    @Test
    void unconfirmDuringTheCountdownStopsIt() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(20);
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        server.getScheduler().performTicks(COUNTDOWN_TICKS * 2);
        assertEquals(TradeState.OPEN, session.state());
    }

    @Test
    void quitOnTheLastCountdownTickReturnsEverything() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS - 1);
        sam.disconnect();
        server.getScheduler().performTicks(5);

        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
        assertEquals(16, count(sam, Material.ENDER_PEARL));
        assertEquals(0, count(alex, Material.ENDER_PEARL));
    }

    @Test
    void deathOnTheLastCountdownTickReturnsEverything() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS - 1);
        alex.setHealth(0);
        server.getScheduler().performTicks(5);

        assertTrue(session.isEnded());
        assertEquals(3, plugin.returnsService().store().readQueue(alex).size(), "the dying player's offer is queued");
        assertEquals(16, count(sam, Material.ENDER_PEARL));
    }

    @Test
    void twoTradesBackToBack() {
        TradeSession first = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);
        assertTrue(first.isEnded());
        server.getScheduler().performTicks(1);

        // Sam gives the diamonds straight back.
        TradeSession second = trades().start(sam, alex);
        int slot = sam.getInventory().first(Material.DIAMOND);
        click(sam, ClickType.LEFT, raw(slot));
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        click(alex, ClickType.LEFT, GuiLayout.CONFIRM);
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertTrue(second.isEnded());
        assertEquals(64, count(alex, Material.DIAMOND));
        assertEquals(16, count(alex, Material.ENDER_PEARL));
        assertCompletedCleanly(second);
    }

    @Test
    void completingTwiceDoesNothingTheSecondTime() {
        TradeSession session = tradeWithItems();
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);
        trades().complete(session);
        assertEquals(16, count(alex, Material.ENDER_PEARL));
        assertEquals(64, count(sam, Material.DIAMOND));
    }

    // --- XP editor ---

    private EditorHolder openEditor() {
        click(alex, ClickType.LEFT, GuiLayout.XP);
        server.getScheduler().performTicks(1);
        EditorHolder editor = EditorHolder.of(alex.getOpenInventory().getTopInventory());
        assertNotNull(editor, "the XP editor opened");
        return editor;
    }

    @Test
    void editorButtonsChangeOnlyTheDraftUntilDone() {
        alex.setLevel(50);
        TradeSession session = start();
        EditorHolder editor = openEditor();

        click(alex, ClickType.LEFT, GuiLayout.plusSlot(3)); // +30
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(1)); // +5
        click(alex, ClickType.LEFT, GuiLayout.minusSlot(0)); // -1
        assertEquals(34, editor.draft());
        assertEquals(0, session.a().offer().xpLevels(), "not offered until Done");
        assertEquals(0, session.version());

        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        server.getScheduler().performTicks(1);

        assertEquals(34, session.a().offer().xpLevels());
        assertEquals(1, session.version());
        assertTrue(isTradeWindow(alex), "back in the trade window");
        assertFalse(session.isEnded());
    }

    @Test
    void editorClampsBetweenZeroAndTheCurrentLevel() {
        alex.setLevel(12);
        start();
        EditorHolder editor = openEditor();
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(3));
        assertEquals(12, editor.draft());
        click(alex, ClickType.LEFT, GuiLayout.minusSlot(3));
        click(alex, ClickType.LEFT, GuiLayout.minusSlot(3));
        assertEquals(0, editor.draft());
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_MAX);
        assertEquals(12, editor.draft());
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_ZERO);
        assertEquals(0, editor.draft());
    }

    @Test
    void backAndEscDiscardTheDraftWithoutEndingTheTrade() {
        alex.setLevel(20);
        TradeSession session = start();
        openEditor();
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(2));
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_BACK);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex));
        assertEquals(0, session.a().offer().xpLevels());

        openEditor();
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(2));
        alex.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex), "Esc goes back to the trade window");
        assertEquals(0, session.a().offer().xpLevels());
        assertFalse(session.isEnded());
        server.getScheduler().performTicks(40);
        assertFalse(session.isEnded(), "the watchdog doesn't end it either");
    }

    @Test
    void openingTheEditorClearsOnlyYourOwnConfirmationAndStopsTheCountdown() {
        alex.setLevel(20);
        TradeSession session = start();
        bothConfirm();
        assertEquals(TradeState.COUNTDOWN, session.state());

        openEditor();

        assertEquals(TradeState.OPEN, session.state());
        assertEquals(Side.NOT_CONFIRMED, session.a().confirmedAtVersion());
        assertEquals(session.version(), session.b().confirmedAtVersion());
        server.getScheduler().performTicks(COUNTDOWN_TICKS * 2);
        assertFalse(session.isEnded());
    }

    @Test
    void editorDoneAfterConfirmingResetsBoth() {
        alex.setLevel(20);
        TradeSession session = start();
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        openEditor();
        click(alex, ClickType.LEFT, GuiLayout.plusSlot(0));
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        assertEquals(Side.NOT_CONFIRMED, session.b().confirmedAtVersion(), "an offer change clears both");
        assertTrue(session.isLocked(session.b()));
    }

    @Test
    void xpButtonNeedsThePermission() {
        alex.setLevel(20);
        alex.addAttachment(plugin, "mrtrades.xp", false);
        start();
        click(alex, ClickType.LEFT, GuiLayout.XP);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex), "no editor without mrtrades.xp");
    }

    @Test
    void quitWhileInTheEditorReturnsEverything() {
        alex.setLevel(20);
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        openEditor();
        alex.disconnect();
        assertTrue(session.isEnded());
        assertEquals(10, count(alex, Material.DIAMOND));
    }
}
