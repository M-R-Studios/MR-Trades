package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.gui.HistoryHolder;
import com.mrstudios.trades.history.Backlog;
import com.mrstudios.trades.history.HistoryQuery;
import com.mrstudios.trades.history.TradeLog;
import com.mrstudios.trades.history.TradeRecord;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The trade log written by completed trades, and {@code /trade history} (DESIGN.md 6, M24). */
class HistoryTest extends TradeFixture {

    private static final int COUNTDOWN_TICKS = 3 * 20;

    @BeforeEach
    void readyLog() throws Exception {
        // The change lock runs on wall-clock time, which mock ticks don't advance.
        setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
        log().flush(5000);
        assertEquals(TradeLog.Status.READY, log().status());
    }

    private TradeLog log() {
        return plugin.tradeLog();
    }

    private List<TradeRecord> logged(PlayerMock player) {
        log().flush(5000);
        return log().page(new HistoryQuery(player.getUniqueId(), player.getName(), 1)).join().rows();
    }

    /** Alex gives a diamond stack and Excalibur for Sam's ender pearls. */
    private TradeSession completeTrade() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        alex.getInventory().setItem(1, excalibur());
        sam.getInventory().setItem(4, new ItemStack(Material.ENDER_PEARL, 16));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(alex, ClickType.LEFT, raw(1));
        click(sam, ClickType.LEFT, raw(4));
        click(alex, ClickType.LEFT, GuiLayout.CONFIRM);
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);
        assertTrue(session.isEnded());
        return session;
    }

    /** Runs the command, waits for the query and the main-thread task that opens the window. */
    private void history(PlayerMock player, String args) {
        player.performCommand(("trade history " + args).trim());
        settle();
    }

    private void settle() {
        log().flush(5000);
        server.getScheduler().performTicks(1);
        log().flush(5000);
        server.getScheduler().performTicks(1);
    }

    private static HistoryHolder historyOf(PlayerMock player) {
        return HistoryHolder.of(player.getOpenInventory().getTopInventory());
    }

    private static Inventory top(PlayerMock player) {
        return player.getOpenInventory().getTopInventory();
    }

    private static String name(ItemStack item) {
        Component name = item.getItemMeta().displayName();
        return name == null ? "" : PlainTextComponentSerializer.plainText().serialize(name);
    }

    private static List<String> lore(ItemStack item) {
        List<String> lines = new ArrayList<>();
        for (Component line : item.getItemMeta().lore()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return lines;
    }

    private void record(PlayerMock a, PlayerMock b, long at, byte[] aItems) {
        log().record(new TradeRecord(UUID.randomUUID(), at, "default", "world", 0, 64, 0, a.getUniqueId(), a.getName(),
                b.getUniqueId(), b.getName(), aItems, new byte[0], "1x Stone", "", BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, false, 0));
    }

    @Test
    void aCompletedTradeIsLoggedWithTheExactItems() {
        TradeSession session = completeTrade();

        List<TradeRecord> rows = logged(alex);
        assertEquals(1, rows.size());
        TradeRecord row = rows.get(0);
        assertEquals(session.id(), row.id());
        assertEquals(alex.getUniqueId(), row.aId());
        assertEquals("Sam", row.bName());
        assertEquals("default", row.server());
        assertEquals("world", row.world());
        assertEquals(List.of(new ItemStack(Material.DIAMOND, 64), excalibur()), TradeRecord.deserialize(row.aItems()));
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), TradeRecord.deserialize(row.bItems()));
        assertEquals("64x Diamond, 1x Diamond Sword \"Excalibur\"", row.aSummary());
        assertEquals("16x Ender Pearl", row.bSummary());
        assertFalse(row.economyError());
        assertEquals(row, logged(sam).get(0), "the same row for both players");
    }

    @Test
    void cancelledTradesAreNotLogged() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        start();
        click(alex, ClickType.LEFT, raw(0));
        alex.performCommand("trade cancel");
        assertEquals(List.of(), logged(alex));
    }

    @Test
    void aBrokenLogNeverStopsATrade() {
        log().close(1000);
        completeTrade();
        assertEquals(16, count(alex, Material.ENDER_PEARL), "the trade went through");
        assertTrue(Files.exists(plugin.getDataFolder().toPath().resolve(Backlog.FILE_NAME)), "the record was kept");
    }

    @Test
    void historyListsTradesWithThePartnerHeadAndSummary() {
        completeTrade();
        history(alex, "");

        HistoryHolder holder = historyOf(alex);
        assertNotNull(holder, "the history window opened");
        assertNull(holder.detail());
        ItemStack entry = top(alex).getItem(0);
        assertEquals(Material.PLAYER_HEAD, entry.getType());
        assertEquals("Trade with Sam", name(entry));
        List<String> lore = lore(entry);
        assertTrue(lore.contains("You gave: 64x Diamond, 1x Diamond Sword \"Excalibur\""), lore.toString());
        assertTrue(lore.contains("You got: 16x Ender Pearl"), lore.toString());
        assertTrue(trades().display().isDisplay(entry));
        assertNull(top(alex).getItem(1));
        assertEquals(Material.BARRIER, top(alex).getItem(GuiLayout.HISTORY_CLOSE).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top(alex).getItem(GuiLayout.HISTORY_NEXT).getType(), "one page");

        history(sam, "");
        assertTrue(lore(top(sam).getItem(0)).contains("You got: 64x Diamond, 1x Diamond Sword \"Excalibur\""));
    }

    @Test
    void moneyAndLevelsAreInTheLore() {
        alex.setLevel(10);
        TradeSession session = start();
        session.setXpLevels(session.a(), 0, 4);
        click(alex, ClickType.LEFT, GuiLayout.CONFIRM);
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);
        history(sam, "");
        List<String> lore = lore(top(sam).getItem(0));
        assertTrue(lore.contains("You gave: nothing"), lore.toString());
        assertTrue(lore.contains("You got: 4 XP levels"), lore.toString());
    }

    @Test
    void theDetailViewShowsWhatWasGivenAndGotAndNothingCanBeTakenOut() {
        completeTrade();
        history(alex, "");
        click(alex, ClickType.LEFT, 0);
        server.getScheduler().performTicks(1);

        HistoryHolder detail = historyOf(alex);
        assertNotNull(detail.detail(), "the details opened");
        Inventory top = top(alex);
        assertEquals(Material.DIAMOND, top.getItem(GuiLayout.detailSlot(true, 0)).getType());
        assertEquals(64, top.getItem(GuiLayout.detailSlot(true, 0)).getAmount());
        assertEquals(Material.DIAMOND_SWORD, top.getItem(GuiLayout.detailSlot(true, 1)).getType());
        assertEquals(Material.ENDER_PEARL, top.getItem(GuiLayout.detailSlot(false, 0)).getType());
        assertTrue(trades().display().isDisplay(top.getItem(GuiLayout.detailSlot(true, 0))));
        assertEquals("You gave", name(top.getItem(GuiLayout.DETAIL_GAVE_INFO)));

        int pearls = count(alex, Material.ENDER_PEARL);
        for (ClickType type : List.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT, ClickType.NUMBER_KEY)) {
            InventoryClickEvent event = click(alex, type, GuiLayout.detailSlot(false, 0));
            assertTrue(event.isCancelled(), type.name());
        }
        server.getScheduler().performTicks(1);
        assertEquals(pearls, count(alex, Material.ENDER_PEARL), "nothing came out");
        assertEquals(0, count(alex, Material.DIAMOND));

        click(alex, ClickType.LEFT, GuiLayout.DETAIL_BACK);
        server.getScheduler().performTicks(1);
        assertNull(historyOf(alex).detail(), "back to the list");
        click(alex, ClickType.LEFT, GuiLayout.HISTORY_CLOSE);
        server.getScheduler().performTicks(1);
        assertNull(historyOf(alex), "closed");
    }

    @Test
    void itemsThatCantBeReadShowABarrier() {
        record(alex, sam, System.currentTimeMillis(), new byte[] {1, 2, 3});
        history(alex, "");
        click(alex, ClickType.LEFT, 0);
        server.getScheduler().performTicks(1);
        ItemStack shown = top(alex).getItem(GuiLayout.detailSlot(true, 0));
        assertEquals(Material.BARRIER, shown.getType());
        assertTrue(name(shown).startsWith("Unreadable item"));
    }

    @Test
    void pagesHold45TradesNewestFirst() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 47; i++) {
            record(alex, sam, now - i * 1000L, new byte[0]);
        }
        history(alex, "");
        assertEquals(Material.ARROW, top(alex).getItem(GuiLayout.HISTORY_NEXT).getType());
        assertEquals(Material.PLAYER_HEAD, top(alex).getItem(44).getType());

        click(alex, ClickType.LEFT, GuiLayout.HISTORY_NEXT);
        settle();
        HistoryHolder second = historyOf(alex);
        assertEquals(2, second.page().query().page());
        assertEquals(2, second.page().rows().size());
        assertEquals(Material.ARROW, top(alex).getItem(GuiLayout.HISTORY_PREVIOUS).getType());

        click(alex, ClickType.LEFT, GuiLayout.HISTORY_PREVIOUS);
        settle();
        assertEquals(1, historyOf(alex).page().query().page());

        history(alex, "2");
        assertEquals(2, historyOf(alex).page().query().page(), "/trade history <page>");
    }

    @Test
    void otherPlayersHistoryNeedsThePermission() {
        completeTrade();
        PlayerMock kim = server.addPlayer("Kim");
        history(kim, "Alex");
        assertEquals(List.of("MR-Trades » You don't have permission to do that."), drain(kim));
        assertNull(historyOf(kim));

        kim.addAttachment(plugin, "mrtrades.history.others", true);
        history(kim, "alex 1");
        HistoryHolder holder = historyOf(kim);
        assertNotNull(holder);
        assertEquals(alex.getUniqueId(), holder.page().query().player());
        assertEquals("Trade with Sam", name(top(kim).getItem(0)), "from Alex's side");
    }

    @Test
    void ownNameNeedsNoExtraPermission() {
        completeTrade();
        history(alex, "ALEX");
        assertNotNull(historyOf(alex));
    }

    @Test
    void noTradesAndUnknownPlayersSaySo() {
        history(alex, "");
        assertEquals(List.of("MR-Trades » No trades yet."), drain(alex));
        alex.addAttachment(plugin, "mrtrades.history.others", true);
        history(alex, "Nobody");
        assertEquals(List.of("MR-Trades » No trades found for Nobody."), drain(alex));
        history(alex, "Sam");
        assertEquals(List.of("MR-Trades » No trades found for Sam."), drain(alex));
    }

    @Test
    void needsTheHistoryPermissionButNotTheTradeOne() {
        completeTrade();
        alex.addAttachment(plugin, "mrtrades.trade", false);
        history(alex, "");
        assertNotNull(historyOf(alex), "staff can look without trading");
        alex.closeInventory();
        drain(alex);
        alex.addAttachment(plugin, "mrtrades.history", false);
        history(alex, "");
        assertEquals(List.of("MR-Trades » You don't have permission to do that."), drain(alex));
    }

    @Test
    void disabledHistorySaysSo() throws Exception {
        setConfig("  enabled: true\n  # Delete entries", "  enabled: false\n  # Delete entries");
        drain(alex);
        history(alex, "");
        assertEquals(List.of("MR-Trades » Trade history is disabled."), drain(alex));
    }

    @Test
    void notWhileTrading() {
        start();
        drain(alex);
        history(alex, "");
        assertEquals(List.of("MR-Trades » You're already trading."), drain(alex));
        assertTrue(isTradeWindow(alex), "the trade window stays");
    }

    @Test
    void aTradeStartedWhileTheQueryRunsWins() {
        completeTrade();
        alex.performCommand("trade history");
        start();
        settle();
        assertTrue(isTradeWindow(alex), "the history doesn't replace the trade window");
    }

    @Test
    void onBedrockSwitchingPagesClosesPausesAndOpens() {
        AmountInputTest.FakeForms forms = new AmountInputTest.FakeForms();
        forms.bedrock.add(alex.getUniqueId());
        plugin.hooks().overrideBedrock(forms);
        completeTrade();
        history(alex, "");
        click(alex, ClickType.LEFT, 0);
        server.getScheduler().performTicks(1);
        assertNull(historyOf(alex), "closed first");
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS);
        assertNotNull(historyOf(alex).detail(), "then the details open");
    }

    @Test
    void historyTabCompletes() {
        assertTrue(server.getCommandTabComplete(alex, "trade h").contains("history"));
    }
}
