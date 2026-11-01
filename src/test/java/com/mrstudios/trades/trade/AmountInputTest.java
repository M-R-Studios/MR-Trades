package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.hook.BedrockForms;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Typing amounts in chat and in Bedrock forms (DESIGN.md 4.1, 4.2, 3.5 #16). */
class AmountInputTest extends TradeFixture {

    private FakeEconomy economy;
    private FakeForms forms;

    /** Records forms instead of sending them; tests answer them by hand. */
    static final class FakeForms implements BedrockForms {
        final List<UUID> bedrock = new ArrayList<>();
        boolean refuse;
        Consumer<Boolean> choice;
        String choiceContent;
        Consumer<String> inputAnswer;
        IntConsumer sliderAnswer;
        int sliderMax = -1;
        Runnable closed;

        @Override
        public boolean isBedrock(UUID player) {
            return bedrock.contains(player);
        }

        @Override
        public boolean sendChoice(UUID player, String title, String content, String first, String second,
                Consumer<Boolean> onAnswer) {
            choice = onAnswer;
            choiceContent = content;
            return !refuse;
        }

        @Override
        public boolean sendInput(UUID player, String title, String label, String current, Consumer<String> onAnswer,
                Runnable onClosed) {
            inputAnswer = onAnswer;
            closed = onClosed;
            return !refuse;
        }

        @Override
        public boolean sendSlider(UUID player, String title, String label, int max, int current, IntConsumer onAnswer,
                Runnable onClosed) {
            sliderAnswer = onAnswer;
            sliderMax = max;
            closed = onClosed;
            return !refuse;
        }
    }

    @BeforeEach
    void setUpHooks() throws Exception {
        economy = new FakeEconomy();
        economy.balances.put(alex.getUniqueId(), new BigDecimal("500.00"));
        plugin.hooks().overrideEconomy(economy);
        forms = new FakeForms();
        plugin.hooks().overrideBedrock(forms);
        setConfig("change-lock-ms: 1000", "change-lock-ms: 0");
        alex.setLevel(20);
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return lines;
    }

    /** Opens Alex's editor of {@code kind} and presses "Type an amount". */
    private void typeAmount(int editorButton) {
        click(alex, ClickType.LEFT, editorButton);
        // Bedrock players' window switches pause a few ticks (BedrockWindowSwitchTest).
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertNotNull(EditorHolder.of(alex.getOpenInventory().getTopInventory()));
        drain(alex);
        click(alex, ClickType.LEFT, GuiLayout.EDITOR_TYPE);
        server.getScheduler().performTicks(1);
    }

    /** Fires a chat message the way the server does (async event, off the main thread) and returns it. */
    private AsyncChatEvent chat(PlayerMock player, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, new HashSet<>(), null, Component.text(text),
                Component.text(text), null);
        Thread chatThread = new Thread(() -> server.getPluginManager().callEvent(event), "test-chat");
        chatThread.start();
        try {
            chatThread.join(5000);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        server.getScheduler().performTicks(1);
        return event;
    }

    private static boolean typing(Side side) {
        return side.view() == ViewState.INPUT_CHAT || side.view() == ViewState.INPUT_FORM;
    }

    @Test
    void typingMoneyInChatAppliesItAndReopensTheWindow() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);

        assertEquals(ViewState.INPUT_CHAT, session.a().view());
        assertFalse(isTradeWindow(alex), "the window closes while typing");
        assertTrue(drain(alex).contains("MR-Trades » Type an amount in chat, or cancel. You have 30 seconds."));
        assertFalse(session.isEnded(), "closing for input never ends the trade");

        AsyncChatEvent event = chat(alex, "1,250.5");
        assertTrue(event.isCancelled());
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("at most $500")), "over the balance");
        assertTrue(typing(session.a()), "still waiting after a too-high amount");

        chat(alex, "250.75");
        server.getScheduler().performTicks(1);

        assertEquals(0, new BigDecimal("250.75").compareTo(session.a().offer().money()));
        assertTrue(isTradeWindow(alex), "back in the trade window");
        assertEquals(ViewState.TRADE, session.a().view());
        assertNull(plugin.trades().bedrockForms(alex, true), "Alex is a Java player");
    }

    @Test
    void invalidAndNegativeAmountsKeepWaiting() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        for (String bad : List.of("lots", "-5", "1e3", "12.345")) {
            AsyncChatEvent event = chat(alex, bad);
            assertTrue(event.isCancelled(), bad + " must not reach public chat");
            List<String> lines = drain(alex);
            assertTrue(lines.contains("MR-Trades » '" + bad + "' isn't a valid amount. Try again or type cancel."),
                    bad + " -> " + lines);
        }
        assertTrue(typing(session.a()));
        assertEquals(0, session.a().offer().money().signum());
    }

    @Test
    void allOffersTheMaximum() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        chat(alex, "ALL");
        server.getScheduler().performTicks(1);
        assertEquals(0, new BigDecimal("500").compareTo(session.a().offer().money()));
    }

    @Test
    void xpTakesWholeLevelsOnly() {
        TradeSession session = start();
        typeAmount(GuiLayout.XP);
        chat(alex, "2.5");
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("'2.5' isn't a valid amount")));
        chat(alex, "21");
        assertTrue(drain(alex).contains("MR-Trades » You can offer at most 20."));
        chat(alex, "12");
        server.getScheduler().performTicks(1);
        assertEquals(12, session.a().offer().xpLevels());
        assertTrue(isTradeWindow(alex));
    }

    @Test
    void cancelWordReopensUnchanged() {
        TradeSession session = start();
        session.setXpLevels(session.a(), 0, 3);
        typeAmount(GuiLayout.XP);
        long version = session.version();
        chat(alex, "Cancel");
        server.getScheduler().performTicks(1);
        assertEquals(3, session.a().offer().xpLevels());
        assertEquals(version, session.version());
        assertTrue(isTradeWindow(alex));
        assertFalse(session.isEnded());
    }

    @Test
    void timeoutReopensUnchanged() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        server.getScheduler().performTicks(30 * 20 + 2);
        assertTrue(drain(alex).contains("MR-Trades » Amount entry timed out."));
        assertTrue(isTradeWindow(alex));
        assertEquals(ViewState.TRADE, session.a().view());
        assertFalse(chat(alex, "100").isCancelled(), "after the timeout chat is normal again");
        assertEquals(0, session.a().offer().money().signum());
    }

    @Test
    void watchdogLeavesATypingPlayerAlone() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        server.getScheduler().performTicks(25 * 20);
        assertFalse(session.isEnded());
    }

    @Test
    void otherPlayerSeesTypingAndCanChangeTheirOffer() {
        sam.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        ItemStack head = sam.getOpenInventory().getTopInventory().getItem(GuiLayout.OTHER_HEAD);
        assertTrue(head.getItemMeta().lore().stream()
                .anyMatch(l -> PlainTextComponentSerializer.plainText().serialize(l).contains("typing an amount")));

        click(sam, ClickType.LEFT, raw(0));
        chat(alex, "10");
        server.getScheduler().performTicks(1);

        assertEquals(5, session.b().offer().item(0).getAmount());
        assertEquals(0, BigDecimal.TEN.compareTo(session.a().offer().money()));
        assertTrue(isTradeWindow(alex));
    }

    @Test
    void quittingWhileTypingEndsTheTradeCleanly() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        typeAmount(GuiLayout.MONEY);
        alex.disconnect();
        assertTrue(session.isEnded());
        assertEquals(7, count(alex, Material.DIAMOND));
        assertFalse(chat(sam, "hello").isCancelled());
    }

    @Test
    void tradeEndingWhileTypingMakesChatNormalAgain() {
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        sam.performCommand("trade cancel");
        assertTrue(session.isEnded());
        assertFalse(chat(alex, "250").isCancelled(), "a late amount is just chat");
        assertEquals(0, session.a().offer().money().signum());
    }

    @Test
    void chatIsUntouchedForPlayersNotTyping() {
        start();
        assertFalse(chat(sam, "hi").isCancelled());
        assertFalse(chat(alex, "hi").isCancelled());
    }

    // --- Bedrock forms ---

    @Test
    void bedrockMoneyFormAppliesAValidAnswer() {
        forms.bedrock.add(alex.getUniqueId());
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);

        assertEquals(ViewState.INPUT_FORM, session.a().view());
        assertNotNull(forms.inputAnswer, "a form was sent instead of a chat prompt");
        assertFalse(chat(alex, "100").isCancelled(), "chat isn't read while a form is open");

        forms.inputAnswer.accept("42.5");
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);

        assertEquals(0, new BigDecimal("42.5").compareTo(session.a().offer().money()));
        assertTrue(isTradeWindow(alex));
    }

    @Test
    void bedrockFormClosedOrInvalidReopensUnchanged() {
        forms.bedrock.add(alex.getUniqueId());
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        forms.closed.run();
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 3);
        assertTrue(isTradeWindow(alex));
        assertEquals(0, session.a().offer().money().signum());

        typeAmount(GuiLayout.MONEY);
        forms.inputAnswer.accept("nonsense");
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 3);
        assertTrue(isTradeWindow(alex));
        assertEquals(0, session.a().offer().money().signum());
    }

    @Test
    void bedrockXpSliderUpToTheirLevel() {
        forms.bedrock.add(alex.getUniqueId());
        TradeSession session = start();
        typeAmount(GuiLayout.XP);
        assertEquals(20, forms.sliderMax);
        forms.sliderAnswer.accept(7);
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertEquals(7, session.a().offer().xpLevels());
    }

    @Test
    void lateFormAnswerAfterTheTradeEndedIsIgnored() {
        forms.bedrock.add(alex.getUniqueId());
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        sam.performCommand("trade cancel");
        forms.inputAnswer.accept("10");
        server.getScheduler().performTicks(TradeService.BEDROCK_SWITCH_TICKS + 2);
        assertTrue(session.isEnded());
        assertEquals(0, session.a().offer().money().signum());
    }

    @Test
    void formThatCantBeSentFallsBackToChat() {
        forms.bedrock.add(alex.getUniqueId());
        forms.refuse = true;
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        assertEquals(ViewState.INPUT_CHAT, session.a().view());
        assertTrue(drain(alex).stream().anyMatch(l -> l.contains("Type an amount in chat")));
        chat(alex, "5");
        server.getScheduler().performTicks(1);
        assertEquals(0, new BigDecimal("5").compareTo(session.a().offer().money()));
    }

    @Test
    void amountFormSettingOffUsesChat() throws Exception {
        setConfig("amount-form: true", "amount-form: false");
        forms.bedrock.add(alex.getUniqueId());
        TradeSession session = start();
        typeAmount(GuiLayout.MONEY);
        assertEquals(ViewState.INPUT_CHAT, session.a().view());
        assertNull(forms.inputAnswer);
    }

    @Test
    void bedrockTargetGetsTheRequestFormAndNoButtons() {
        forms.bedrock.add(sam.getUniqueId());
        drain(sam);
        alex.performCommand("trade Sam");

        List<String> lines = drain(sam);
        assertEquals(1, lines.size(), "only request-received (with the typed commands), no [Accept] [Deny] line");
        assertTrue(lines.get(0).contains("/trade accept Alex"));
        assertEquals("Alex wants to trade with you.", forms.choiceContent);

        forms.choice.accept(true);
        server.getScheduler().performTicks(1);
        assertTrue(trades().registry().isTrading(sam.getUniqueId()), "Accept starts the trade");
    }

    @Test
    void requestFormDenyDenies() {
        forms.bedrock.add(sam.getUniqueId());
        alex.performCommand("trade Sam");
        drain(alex);
        forms.choice.accept(false);
        server.getScheduler().performTicks(1);
        assertTrue(drain(alex).contains("MR-Trades » Sam denied your trade request."));
        assertFalse(trades().registry().isTrading(sam.getUniqueId()));
    }

    @Test
    void requestFormAcceptIsCheckedAgain() {
        forms.bedrock.add(sam.getUniqueId());
        alex.performCommand("trade Sam");
        alex.setLocation(alex.getLocation().add(40, 0, 0));
        forms.choice.accept(true);
        server.getScheduler().performTicks(1);
        assertFalse(trades().registry().isTrading(sam.getUniqueId()), "too far now: refused like the command");
    }

    @Test
    void javaTargetGetsButtonsAndNoForm() {
        drain(sam);
        alex.performCommand("trade Sam");
        assertEquals(2, drain(sam).size());
        assertNull(forms.choice);
    }
}
