package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.trade.TradeSession.ChangeResult;
import com.mrstudios.trades.trade.TradeSession.ConfirmResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/** The confirm/version/lock state machine (DESIGN.md 3.4), with a fake clock. */
class TradeSessionTest {

    private static final long LOCK = 1000;

    private long now = 1_000_000;
    private TradeSession session;
    private Side a;
    private Side b;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        session = new TradeSession(UUID.randomUUID(), UUID.randomUUID(), "Alex", UUID.randomUUID(), "Sam", () -> now);
        a = session.a();
        b = session.b();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Both confirm the current offers; the countdown is now running. */
    private void bothConfirm() {
        now += LOCK;
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(a));
        assertEquals(ConfirmResult.BOTH_CONFIRMED, session.toggleConfirm(b));
        assertEquals(TradeState.COUNTDOWN, session.state());
    }

    /** A named mutation that changes an offer, run by either side. */
    private record Mutation(String name, Function<TradeSessionTest, ChangeResult> apply) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> mutations() {
        return Stream.of(true, false).flatMap(byA -> Stream.of(
                new Mutation("add stack", t -> t.session.addItem(t.side(byA), LOCK, new ItemStack(Material.EMERALD, 5))),
                new Mutation("add to existing stack",
                        t -> t.session.addItem(t.side(byA), LOCK, new ItemStack(Material.DIAMOND, 1))),
                new Mutation("remove one", t -> t.session.removeItem(t.side(byA), LOCK, 0, 1) != null
                        ? ChangeResult.CHANGED : ChangeResult.UNCHANGED),
                new Mutation("remove stack", t -> t.session.removeItem(t.side(byA), LOCK, 0, 64) != null
                        ? ChangeResult.CHANGED : ChangeResult.UNCHANGED),
                new Mutation("money", t -> t.session.setMoney(t.side(byA), LOCK, new BigDecimal("12.50"))),
                new Mutation("xp", t -> t.session.setXpLevels(t.side(byA), LOCK, 3)))
                .map(m -> Arguments.of(byA ? "A" : "B", m)));
    }

    private Side side(boolean isA) {
        return isA ? a : b;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mutations")
    void everyChangeAfterBothConfirmedResetsBothAndStopsTheCountdown(String who, Mutation mutation) {
        session.addItem(a, LOCK, new ItemStack(Material.DIAMOND, 5));
        session.addItem(b, LOCK, new ItemStack(Material.DIAMOND, 5));
        bothConfirm();
        AtomicInteger cancelled = new AtomicInteger();
        session.attachCountdown(cancelled::incrementAndGet);
        long version = session.version();

        assertEquals(ChangeResult.CHANGED, mutation.apply().apply(this));

        assertEquals(version + 1, session.version());
        assertEquals(Side.NOT_CONFIRMED, a.confirmedAtVersion());
        assertEquals(Side.NOT_CONFIRMED, b.confirmedAtVersion());
        assertEquals(TradeState.OPEN, session.state());
        assertEquals(1, cancelled.get());
        assertFalse(session.beginCompleting());
        Side changer = who.equals("A") ? a : b;
        assertTrue(session.isLocked(session.other(changer)));
        assertFalse(session.isLocked(changer));
    }

    @Test
    void changeLockBlocksOnlyTheOtherSideUntilItExpires() {
        session.addItem(a, LOCK, new ItemStack(Material.DIAMOND, 1));

        assertTrue(session.isLocked(b));
        assertFalse(session.isLocked(a));
        assertEquals(ConfirmResult.LOCKED, session.toggleConfirm(b));
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(a), "the changer can confirm at once");

        now += LOCK - 1;
        assertEquals(ConfirmResult.LOCKED, session.toggleConfirm(b));
        now += 1;
        assertEquals(ConfirmResult.BOTH_CONFIRMED, session.toggleConfirm(b));
    }

    @Test
    void zeroChangeLockLocksNothing() {
        session.addItem(a, 0, new ItemStack(Material.DIAMOND, 1));

        assertFalse(session.isLocked(b));
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(b));
    }

    @Test
    void eachNewChangeExtendsTheLock() {
        session.setMoney(a, LOCK, BigDecimal.ONE);
        now += 900;
        session.setMoney(a, LOCK, BigDecimal.TEN);
        now += 900;
        assertEquals(ConfirmResult.LOCKED, session.toggleConfirm(b));
    }

    @Test
    void confirmToggles() {
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(a));
        assertEquals(session.version(), a.confirmedAtVersion());
        assertEquals(ConfirmResult.UNCONFIRMED, session.toggleConfirm(a));
        assertEquals(Side.NOT_CONFIRMED, a.confirmedAtVersion());
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(a));
    }

    @Test
    void unconfirmDuringCountdownStopsIt() {
        bothConfirm();
        AtomicInteger cancelled = new AtomicInteger();
        session.attachCountdown(cancelled::incrementAndGet);

        assertEquals(ConfirmResult.UNCONFIRMED, session.toggleConfirm(b));

        assertEquals(TradeState.OPEN, session.state());
        assertEquals(1, cancelled.get());
        assertEquals(session.version(), a.confirmedAtVersion(), "the other side's confirmation stays");
        assertFalse(session.beginCompleting());
    }

    @Test
    void confirmationFromAnOldVersionNeverCounts() {
        session.addItem(a, 0, new ItemStack(Material.DIAMOND, 1));
        // A stale confirmation, as if a reset had been missed.
        a.confirmAt(session.version() - 1);

        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(b));
        assertFalse(session.bothConfirmed());
        assertEquals(TradeState.OPEN, session.state());
        assertFalse(session.beginCompleting());
    }

    @Test
    void staleConfirmationCantCompleteEvenDuringACountdown() {
        bothConfirm();
        // Simulate a missed reset: the version moves on but the confirmations don't.
        a.confirmAt(session.version() - 1);

        assertFalse(session.beginCompleting());
        assertEquals(TradeState.COUNTDOWN, session.state());
    }

    @Test
    void confirmRaceLastSecondChangeWins() {
        // A and B both confirm, then in the same tick B swaps the diamonds out.
        session.addItem(b, LOCK, new ItemStack(Material.DIAMOND, 64));
        bothConfirm();
        ItemStack taken = session.removeItem(b, LOCK, 0, 64);

        assertEquals(64, taken.getAmount());
        assertFalse(session.beginCompleting(), "the countdown's last tick must not complete the changed trade");
        assertEquals(ConfirmResult.LOCKED, session.toggleConfirm(a), "A must look at the new offer first");
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(b));
        now += LOCK;
        assertEquals(ConfirmResult.BOTH_CONFIRMED, session.toggleConfirm(a));
        assertTrue(session.beginCompleting());
        assertFalse(session.b().offer().hasItems());
    }

    @Test
    void changeBetweenTheTwoConfirmsMeansStartingOver() {
        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(a));
        session.setXpLevels(b, LOCK, 5);
        now += LOCK;

        assertEquals(ConfirmResult.CONFIRMED, session.toggleConfirm(b), "only B, A's confirm was cleared");
        assertEquals(TradeState.OPEN, session.state());
        assertEquals(ConfirmResult.BOTH_CONFIRMED, session.toggleConfirm(a));
    }

    @Test
    void unchangedMutationResetsNothing() {
        session.setMoney(a, LOCK, BigDecimal.TEN);
        bothConfirm();
        long version = session.version();

        assertEquals(ChangeResult.UNCHANGED, session.setMoney(a, LOCK, new BigDecimal("10.00")));
        assertEquals(ChangeResult.UNCHANGED, session.setXpLevels(b, LOCK, 0));
        assertNull(session.removeItem(a, LOCK, 0, 1), "there's no stack 0");
        assertEquals(ChangeResult.UNCHANGED, session.addItem(a, LOCK, new ItemStack(Material.AIR)));

        assertEquals(version, session.version());
        assertEquals(TradeState.COUNTDOWN, session.state());
        assertFalse(session.isLocked(b));
    }

    @Test
    void negativeAmountsAreRefusedByTheOffer() {
        assertEquals(ChangeResult.UNCHANGED, session.setMoney(a, LOCK, new BigDecimal("-1")));
        assertEquals(ChangeResult.UNCHANGED, session.setXpLevels(a, LOCK, -1));
        assertEquals(BigDecimal.ZERO, a.offer().money());
    }

    @Test
    void changesAreRefusedWhileCompletingAndAfterTheEnd() {
        session.addItem(a, LOCK, new ItemStack(Material.DIAMOND, 5));
        bothConfirm();
        assertTrue(session.beginCompleting());
        long version = session.version();
        AtomicInteger ran = new AtomicInteger();

        assertEquals(ChangeResult.REFUSED, session.changeOffer(a, LOCK, offer -> ran.incrementAndGet() > 0));
        assertEquals(ChangeResult.REFUSED, session.setMoney(b, LOCK, BigDecimal.ONE));
        assertNull(session.removeItem(a, LOCK, 0, 5));
        assertEquals(ConfirmResult.IGNORED, session.toggleConfirm(a));
        assertEquals(0, ran.get(), "the mutation must not even run");
        assertEquals(version, session.version());
        assertEquals(5, a.offer().item(0).getAmount());

        session.end();
        assertEquals(ChangeResult.REFUSED, session.addItem(b, LOCK, new ItemStack(Material.DIRT)));
        assertEquals(ConfirmResult.IGNORED, session.toggleConfirm(b));
        assertFalse(session.acceptsChanges());
    }

    @Test
    void clearingOwnConfirmationForAnEditorLeavesTheOtherSide() {
        bothConfirm();
        AtomicInteger cancelled = new AtomicInteger();
        session.attachCountdown(cancelled::incrementAndGet);
        long version = session.version();

        session.clearConfirmation(a);

        assertEquals(Side.NOT_CONFIRMED, a.confirmedAtVersion());
        assertEquals(version, b.confirmedAtVersion());
        assertEquals(version, session.version(), "opening an editor isn't an offer change");
        assertEquals(TradeState.OPEN, session.state());
        assertEquals(1, cancelled.get());
        assertFalse(session.isLocked(b));
    }

    @Test
    void completionFailureGoesBackToOpenWithNoConfirmations() {
        bothConfirm();
        assertTrue(session.beginCompleting());
        assertEquals(TradeState.COMPLETING, session.state());

        session.completionFailed();

        assertEquals(TradeState.OPEN, session.state());
        assertFalse(session.bothConfirmed());
        assertEquals(Side.NOT_CONFIRMED, a.confirmedAtVersion());
        assertThrows(IllegalStateException.class, session::completionFailed);
    }

    @Test
    void beginCompletingOnlyFromACountdown() {
        assertFalse(session.beginCompleting());
        bothConfirm();
        assertTrue(session.beginCompleting());
        assertFalse(session.beginCompleting(), "can't re-enter");
    }

    @Test
    void beginCompletingCancelsTheCountdownTask() {
        bothConfirm();
        AtomicInteger cancelled = new AtomicInteger();
        session.attachCountdown(cancelled::incrementAndGet);

        assertTrue(session.beginCompleting());
        assertEquals(1, cancelled.get());
    }

    @Test
    void endIsIdempotentAndCancelsTheCountdown() {
        bothConfirm();
        AtomicInteger cancelled = new AtomicInteger();
        session.attachCountdown(cancelled::incrementAndGet);

        assertTrue(session.end());
        assertFalse(session.end());
        assertEquals(TradeState.ENDED, session.state());
        assertEquals(1, cancelled.get());
    }

    @Test
    void countdownCanOnlyBeAttachedDuringACountdownAndReplacesTheOldOne() {
        AtomicInteger first = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> session.attachCountdown(first::incrementAndGet));
        assertEquals(1, first.get(), "a task that can't be attached is cancelled, not leaked");

        bothConfirm();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();
        session.attachCountdown(second::incrementAndGet);
        session.attachCountdown(third::incrementAndGet);
        assertEquals(1, second.get());
        assertEquals(0, third.get());
    }

    @Test
    void itemsCanOnlyLeaveACompletingOrEndedTrade() {
        session.addItem(a, LOCK, new ItemStack(Material.DIAMOND, 5));
        assertThrows(IllegalStateException.class, () -> session.takeItems(a));

        session.end();
        List<ItemStack> taken = session.takeItems(a);
        assertEquals(List.of(new ItemStack(Material.DIAMOND, 5)), taken);
        assertEquals(List.of(), session.takeItems(a), "a second take gets nothing: no double return");
    }

    @Test
    void sidesAndLookup() {
        assertSame(a, session.side(a.playerId()));
        assertSame(b, session.side(b.playerId()));
        assertNull(session.side(UUID.randomUUID()));
        assertSame(b, session.other(a));
        assertSame(a, session.other(b));
        assertEquals("Alex", a.name());
        assertEquals(ViewState.TRADE, a.view());
    }

    @Test
    void sideFromAnotherSessionIsRejected() {
        TradeSession other = new TradeSession(UUID.randomUUID(), UUID.randomUUID(), "X", UUID.randomUUID(), "Y", () -> now);
        assertThrows(IllegalArgumentException.class, () -> session.toggleConfirm(other.a()));
        assertThrows(IllegalArgumentException.class, () -> session.setMoney(other.b(), LOCK, BigDecimal.ONE));
    }

    @Test
    void selfTradeIsRejected() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TradeSession(UUID.randomUUID(), id, "A", id, "A", () -> now));
    }
}
