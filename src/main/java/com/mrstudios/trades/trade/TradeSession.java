package com.mrstudios.trades.trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.bukkit.inventory.ItemStack;

/**
 * The state of one trade between two players: their offers, who confirmed which version, and
 * where the trade is in its life ({@link TradeState}). No Bukkit calls: the service around it
 * moves items in and out of inventories, runs the countdown task and renders the views.
 *
 * <p>Every offer change goes through {@link #changeOffer}, which bumps {@link #version()} and
 * clears both confirmations. A confirmation stores the version it confirmed, so a stale one can
 * never complete a changed trade, even if a reset were somehow missed. Main thread only.
 */
public final class TradeSession {

    /** A running countdown, so the session can stop it when the trade changes or ends. */
    @FunctionalInterface
    public interface Countdown {
        void cancel();
    }

    public enum ChangeResult {
        /** The offer changed: version bumped, both confirmations cleared, countdown stopped. */
        CHANGED,
        /** The mutation didn't change anything (e.g. the same amount again). Nothing reset. */
        UNCHANGED,
        /** The trade is completing or ended; the mutation was not run. */
        REFUSED
    }

    public enum ConfirmResult {
        /** This side now confirms the current version; waiting for the other side. */
        CONFIRMED,
        /** This side took back its confirmation (and any countdown stopped). */
        UNCONFIRMED,
        /** Both sides confirm the current version: the state is now {@link TradeState#COUNTDOWN}. */
        BOTH_CONFIRMED,
        /** The other side changed its offer moments ago; confirming is locked for a moment. */
        LOCKED,
        /** The trade is completing or ended. */
        IGNORED
    }

    private final UUID id;
    private final Side a;
    private final Side b;
    private final LongSupplier clock;
    private TradeState state = TradeState.OPEN;
    private long version;
    private Countdown countdown;
    private int countdownRemaining;

    /**
     * @param a the player who sent the request
     * @param b the player who accepted it
     * @param clock epoch millis; {@code System::currentTimeMillis} outside tests
     */
    public TradeSession(UUID id, UUID a, String aName, UUID b, String bName, LongSupplier clock) {
        if (a.equals(b)) {
            throw new IllegalArgumentException("A player can't trade with themselves");
        }
        this.id = Objects.requireNonNull(id);
        this.a = new Side(a, aName);
        this.b = new Side(b, bName);
        this.clock = Objects.requireNonNull(clock);
    }

    public UUID id() {
        return id;
    }

    public Side a() {
        return a;
    }

    public Side b() {
        return b;
    }

    public TradeState state() {
        return state;
    }

    /** Incremented on every offer change. */
    public long version() {
        return version;
    }

    public boolean isEnded() {
        return state == TradeState.ENDED;
    }

    /** Whether offers may change right now ({@link TradeState#OPEN} or {@link TradeState#COUNTDOWN}). */
    public boolean acceptsChanges() {
        return state == TradeState.OPEN || state == TradeState.COUNTDOWN;
    }

    /** The side of {@code player}, or null if they aren't in this trade. */
    public Side side(UUID player) {
        if (a.playerId().equals(player)) {
            return a;
        }
        if (b.playerId().equals(player)) {
            return b;
        }
        return null;
    }

    public Side other(Side side) {
        return side == a ? b : a;
    }

    /** Whether both sides confirm the current version. */
    public boolean bothConfirmed() {
        return a.confirmedAtVersion() == version && b.confirmedAtVersion() == version;
    }

    /** Whether {@code side} can't confirm right now because the other side just changed its offer. */
    public boolean isLocked(Side side) {
        return clock.getAsLong() < side.lockedUntilMillis();
    }

    /**
     * The only way an offer changes (DESIGN.md 3.4). Runs {@code mutation} on {@code side}'s offer;
     * if it reports a change, bumps the version, clears both confirmations, stops the countdown and
     * locks the <em>other</em> side's confirm for {@code changeLockMs}.
     *
     * @param mutation changes the offer and returns whether anything changed
     */
    public ChangeResult changeOffer(Side side, long changeLockMs, Predicate<Offer> mutation) {
        requireOwnSide(side);
        if (!acceptsChanges()) {
            return ChangeResult.REFUSED;
        }
        if (!mutation.test(side.offer())) {
            return ChangeResult.UNCHANGED;
        }
        version++;
        a.clearConfirmation();
        b.clearConfirmation();
        stopCountdown();
        if (changeLockMs > 0) {
            other(side).lockUntil(clock.getAsLong() + changeLockMs);
        }
        return ChangeResult.CHANGED;
    }

    /** Adds {@code moved} to {@code side}'s offer. See {@link Offer#add}. */
    public ChangeResult addItem(Side side, long changeLockMs, ItemStack moved) {
        return changeOffer(side, changeLockMs, offer -> offer.add(moved));
    }

    /**
     * Takes {@code amount} items from the offered stack at {@code index} (the whole stack if it
     * holds fewer). The caller must put the returned items somewhere.
     *
     * @return the items taken out, or null if nothing changed (no such stack, or not accepting changes)
     */
    public ItemStack removeItem(Side side, long changeLockMs, int index, int amount) {
        ItemStack[] taken = new ItemStack[1];
        changeOffer(side, changeLockMs, offer -> (taken[0] = offer.remove(index, amount)) != null);
        return taken[0];
    }

    public ChangeResult setMoney(Side side, long changeLockMs, BigDecimal amount) {
        return changeOffer(side, changeLockMs, offer -> offer.setMoney(amount));
    }

    public ChangeResult setXpLevels(Side side, long changeLockMs, int levels) {
        return changeOffer(side, changeLockMs, offer -> offer.setXpLevels(levels));
    }

    /** Toggles {@code side}'s confirmation of the current version (DESIGN.md 3.4). */
    public ConfirmResult toggleConfirm(Side side) {
        requireOwnSide(side);
        if (!acceptsChanges()) {
            return ConfirmResult.IGNORED;
        }
        if (side.confirmedAtVersion() == version) {
            side.clearConfirmation();
            stopCountdown();
            return ConfirmResult.UNCONFIRMED;
        }
        if (isLocked(side)) {
            return ConfirmResult.LOCKED;
        }
        side.confirmAt(version);
        if (bothConfirmed()) {
            state = TradeState.COUNTDOWN;
            return ConfirmResult.BOTH_CONFIRMED;
        }
        return ConfirmResult.CONFIRMED;
    }

    /**
     * Clears only {@code side}'s confirmation and stops the countdown, e.g. when they open a money
     * or XP editor, so a countdown can't finish while they're changing an amount.
     */
    public void clearConfirmation(Side side) {
        requireOwnSide(side);
        if (!acceptsChanges()) {
            return;
        }
        side.clearConfirmation();
        stopCountdown();
    }

    /** Seconds left in the running countdown, for display. Meaningless outside {@link TradeState#COUNTDOWN}. */
    public int countdownRemaining() {
        return countdownRemaining;
    }

    public void setCountdownRemaining(int seconds) {
        this.countdownRemaining = seconds;
    }

    /** Remembers the countdown task started after {@link ConfirmResult#BOTH_CONFIRMED}. */
    public void attachCountdown(Countdown task) {
        if (state != TradeState.COUNTDOWN) {
            task.cancel();
            throw new IllegalStateException("No countdown to attach to in state " + state);
        }
        if (countdown != null) {
            countdown.cancel();
        }
        countdown = task;
    }

    /**
     * Step 1 of completion: moves to {@link TradeState#COMPLETING} if the countdown is running and
     * both sides still confirm the current version. From then on nothing can change the offers.
     *
     * @return false if the trade must not complete now
     */
    public boolean beginCompleting() {
        if (state != TradeState.COUNTDOWN || !bothConfirmed()) {
            return false;
        }
        cancelCountdownTask();
        state = TradeState.COMPLETING;
        return true;
    }

    /**
     * Completion couldn't run (no space, money or levels): back to {@link TradeState#OPEN} with both
     * confirmations cleared, so both players confirm again once it's fixed.
     */
    public void completionFailed() {
        if (state != TradeState.COMPLETING) {
            throw new IllegalStateException("Not completing (" + state + ")");
        }
        a.clearConfirmation();
        b.clearConfirmation();
        state = TradeState.OPEN;
    }

    /**
     * Marks the trade ended and stops the countdown. Idempotent.
     *
     * @return true the first time, false if it had already ended
     */
    public boolean end() {
        if (state == TradeState.ENDED) {
            return false;
        }
        state = TradeState.ENDED;
        cancelCountdownTask();
        return true;
    }

    /** Takes {@code side}'s items out of the offer, for returning or handing over. */
    public List<ItemStack> takeItems(Side side) {
        requireOwnSide(side);
        if (state != TradeState.COMPLETING && state != TradeState.ENDED) {
            throw new IllegalStateException("Items can only leave a completing or ended trade (" + state + ")");
        }
        return side.offer().takeAllItems();
    }

    private void stopCountdown() {
        cancelCountdownTask();
        if (state == TradeState.COUNTDOWN) {
            state = TradeState.OPEN;
        }
    }

    private void cancelCountdownTask() {
        if (countdown != null) {
            Countdown task = countdown;
            countdown = null;
            task.cancel();
        }
    }

    private void requireOwnSide(Side side) {
        if (side != a && side != b) {
            throw new IllegalArgumentException("Side is not part of this trade");
        }
    }
}
