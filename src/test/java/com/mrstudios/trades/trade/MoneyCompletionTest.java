package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.trade.FakeEconomy.Mode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Money in trades end to end, with a fake economy in place of Vault (DESIGN.md 3.3.1, 3.5.1, #17). */
class MoneyCompletionTest extends TradeFixture {

    private static final int COUNTDOWN_TICKS = 3 * 20;
    private FakeEconomy economy;

    @BeforeEach
    void setUpEconomy() throws Exception {
        economy = new FakeEconomy();
        economy.balances.put(alex.getUniqueId(), new BigDecimal("1000.00"));
        economy.balances.put(sam.getUniqueId(), new BigDecimal("50.00"));
        plugin.hooks().overrideEconomy(economy);
        setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
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

    private void assertBalances(String alexBalance, String samBalance) {
        assertEquals(0, new BigDecimal(alexBalance).compareTo(economy.balanceOf(alex.getUniqueId())), "Alex");
        assertEquals(0, new BigDecimal(samBalance).compareTo(economy.balanceOf(sam.getUniqueId())), "Sam");
    }

    private EditorHolder openMoneyEditor(PlayerMock player) {
        click(player, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(1);
        EditorHolder editor = EditorHolder.of(player.getOpenInventory().getTopInventory());
        assertNotNull(editor, "money editor opened");
        assertEquals(EditorHolder.Kind.MONEY, editor.kind());
        return editor;
    }

    @Test
    void moneyItemsAndXpInOneTrade() {
        alex.getInventory().setItem(0, excalibur());
        alex.setLevel(10);
        sam.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(sam, ClickType.LEFT, raw(0));
        session.setMoney(session.a(), 0, new BigDecimal("250"));
        session.setMoney(session.b(), 0, new BigDecimal("20"));
        session.setXpLevels(session.a(), 0, 4);
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertTrue(session.isEnded());
        assertBalances("770", "280");
        assertEquals(List.of("withdraw 230.00", "deposit 230.00"), economy.calls, "only the net moves");
        assertEquals(6, alex.getLevel());
        assertEquals(4, sam.getLevel());
        assertEquals(64, count(alex, Material.DIAMOND));
        assertTrue(nonEmpty(sam.getInventory().getStorageContents()).contains(excalibur()));
    }

    @Test
    void notEnoughMoneyAtCompletionKeepsTheTradeOpen() {
        TradeSession session = start();
        session.setMoney(session.b(), 0, new BigDecimal("40"));
        bothConfirm();
        economy.balances.put(sam.getUniqueId(), new BigDecimal("10")); // spent during the countdown
        drain(alex);
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertEquals(TradeState.OPEN, session.state());
        assertFalse(session.bothConfirmed());
        assertBalances("1000", "10");
        assertEquals(List.of(), economy.calls, "the validator refused before any transfer");
        assertTrue(drain(alex).contains("MR-Trades » Sam doesn't have enough money. Confirm again after changing the offer."));
    }

    @Test
    void failedDepositRollsBackMoneyAndMovesNoItems() {
        alex.getInventory().setItem(0, excalibur());
        sam.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(sam, ClickType.LEFT, raw(0));
        session.setMoney(session.a(), 0, new BigDecimal("100"));
        economy.plan.put(2, Mode.THROW); // the deposit to Sam throws
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertEquals(TradeState.OPEN, session.state(), "back to open, confirm again");
        assertBalances("1000", "50");
        assertEquals(1, session.a().offer().stackCount(), "items still in the offers");
        assertEquals(1, session.b().offer().stackCount());
        assertEquals(0, count(alex, Material.DIAMOND));
        assertFalse(nonEmpty(sam.getInventory().getStorageContents()).contains(excalibur()));
        assertEquals(0, alex.getLevel());
    }

    @Test
    void failedRefundTellsBothToContactStaffAndMovesNoItems() {
        alex.getInventory().setItem(0, excalibur());
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        session.setMoney(session.a(), 0, new BigDecimal("100"));
        economy.plan.put(2, Mode.FAIL);
        economy.plan.put(3, Mode.FAIL);
        drain(sam);
        bothConfirm();
        server.getScheduler().performTicks(COUNTDOWN_TICKS + 1);

        assertEquals(TradeState.OPEN, session.state());
        assertBalances("900", "50");
        assertTrue(drain(sam).stream().anyMatch(l -> l.contains("Contact staff with trade id " + session.id())));
        assertEquals(1, session.a().offer().stackCount(), "items are never handed out after an economy error");
    }

    @Test
    void economyGoingAwaySetsMoneyOffersToZero() {
        TradeSession session = start();
        session.setMoney(session.a(), 0, new BigDecimal("25"));
        bothConfirm();
        economy.available = false;
        drain(alex);

        trades().economyCheck();

        assertEquals(0, session.a().offer().money().signum());
        assertEquals(TradeState.OPEN, session.state(), "an offer change: confirmations reset");
        assertTrue(drain(alex).contains("MR-Trades » Money trading is unavailable right now. Money offers were set to 0."));
    }

    @Test
    void moneyEditorClampsToTheBalanceAndAppliesOnDone() {
        TradeSession session = start();
        EditorHolder editor = openMoneyEditor(sam);

        click(sam, ClickType.LEFT, GuiLayout.plusSlot(1)); // +10
        click(sam, ClickType.LEFT, GuiLayout.plusSlot(1));
        click(sam, ClickType.LEFT, GuiLayout.plusSlot(0)); // +1
        assertEquals(0, new BigDecimal("21").compareTo(editor.draft()));
        click(sam, ClickType.LEFT, GuiLayout.plusSlot(3)); // +1000, clamped to the balance
        assertEquals(0, new BigDecimal("50").compareTo(editor.draft()));
        click(sam, ClickType.LEFT, GuiLayout.minusSlot(2)); // -100
        assertEquals(0, editor.draft().signum());
        click(sam, ClickType.LEFT, GuiLayout.EDITOR_MAX);
        click(sam, ClickType.LEFT, GuiLayout.EDITOR_DONE);
        server.getScheduler().performTicks(1);

        assertEquals(0, new BigDecimal("50").compareTo(session.b().offer().money()));
        assertTrue(isTradeWindow(sam));
    }

    @Test
    void maxAmountCapsTheEditor() throws Exception {
        setConfig("max-amount: 0", "max-amount: 300");
        start();
        EditorHolder editor = openMoneyEditor(alex);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_MAX);
        assertEquals(0, new BigDecimal("300").compareTo(editor.draft()));
    }

    @Test
    void moneyButtonsAreHiddenWithoutAnEconomy() {
        plugin.hooks().overrideEconomy(null);
        start();
        assertNull(alex.getOpenInventory().getTopInventory().getItem(GuiLayout.MONEY).getItemMeta().displayName()
                .children().stream().findAny().orElse(null), "filler, not a money button");
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex), "no editor");
    }

    @Test
    void moneyButtonNeedsThePermission() {
        alex.addAttachment(plugin, "mrtrades.money", false);
        start();
        click(alex, ClickType.LEFT, GuiLayout.MONEY);
        server.getScheduler().performTicks(1);
        assertTrue(isTradeWindow(alex));
        assertEquals(Material.GOLD_INGOT,
                sam.getOpenInventory().getTopInventory().getItem(GuiLayout.MONEY).getType(),
                "Sam still has the button");
    }

    @Test
    void moneyOfferIsShownToTheOtherPlayer() {
        TradeSession session = start();
        session.setMoney(session.a(), 0, new BigDecimal("12.50"));
        trades().render(session);
        ItemStack shown = sam.getOpenInventory().getTopInventory().getItem(GuiLayout.OTHER_MONEY);
        assertEquals(Material.GOLD_INGOT, shown.getType());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(shown.getItemMeta().displayName()).contains("$12.50"));
    }
}
