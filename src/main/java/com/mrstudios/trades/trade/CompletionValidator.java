package com.mrstudios.trades.trade;

import com.mrstudios.trades.rules.LimitsCheck;
import com.mrstudios.trades.trade.CompletionSnapshot.Trader;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Step 2 of atomic completion (DESIGN.md 3.5.1): decides from a {@link CompletionSnapshot}
 * whether the swap can run right now. Pure; it changes nothing.
 */
public final class CompletionValidator {

    public enum Reason {
        PLAYER_OFFLINE(true),
        PLAYER_DEAD(true),
        BLOCKED_GAMEMODE(true),
        /** A side didn't confirm the current version. */
        NOT_CONFIRMED(false),
        DISABLED_WORLD(true),
        OTHER_WORLD(true),
        TOO_FAR(true),
        /** {@link Result#player()} hasn't room for what they'd receive. */
        NO_SPACE(false),
        /** Money is offered but no economy is installed and enabled. */
        ECONOMY_UNAVAILABLE(false),
        /** {@link Result#player()} can't pay the net amount they owe. */
        NO_MONEY(false),
        /** {@link Result#player()} has fewer levels than they offer. */
        NO_XP(false);

        private final boolean abortsTrade;

        Reason(boolean abortsTrade) {
            this.abortsTrade = abortsTrade;
        }

        /**
         * True: end the trade and give everything back. False: keep it open, clear both
         * confirmations and tell both players why, so they can fix it and confirm again.
         */
        public boolean abortsTrade() {
            return abortsTrade;
        }
    }

    /**
     * @param reason null when the trade can complete
     * @param player whose problem it is, or null when it isn't one player's (or it's OK)
     */
    public record Result(Reason reason, UUID player) {

        public static final Result OK = new Result(null, null);

        public boolean ok() {
            return reason == null;
        }
    }

    private CompletionValidator() {
    }

    public static Result validate(CompletionSnapshot s) {
        Trader a = s.a();
        Trader b = s.b();
        for (Trader t : new Trader[] {a, b}) {
            if (!t.online()) {
                return new Result(Reason.PLAYER_OFFLINE, t.id());
            }
        }
        for (Trader t : new Trader[] {a, b}) {
            if (t.dead()) {
                return new Result(Reason.PLAYER_DEAD, t.id());
            }
        }
        for (Trader t : new Trader[] {a, b}) {
            if (t.gameMode() == null || s.limits().blockedGameModes().contains(t.gameMode())) {
                return new Result(Reason.BLOCKED_GAMEMODE, t.id());
            }
        }
        for (Trader t : new Trader[] {a, b}) {
            if (t.confirmedAtVersion() != s.version()) {
                return new Result(Reason.NOT_CONFIRMED, t.id());
            }
        }
        if (a.position() == null || b.position() == null) {
            return new Result(Reason.PLAYER_OFFLINE, a.position() == null ? a.id() : b.id());
        }
        LimitsCheck.Failure limits = LimitsCheck.check(s.limits(), a.position(), b.position(),
                s.bypassDistance(), a.bypassWorld(), b.bypassWorld());
        if (limits != null) {
            return new Result(switch (limits) {
                case DISABLED_WORLD -> Reason.DISABLED_WORLD;
                case OTHER_WORLD -> Reason.OTHER_WORLD;
                case TOO_FAR -> Reason.TOO_FAR;
            }, null);
        }
        // Each side receives what the other offers.
        if (!InventorySimulator.fits(a.storage(), b.offeredItems(), s.inventoryMaxStack())) {
            return new Result(Reason.NO_SPACE, a.id());
        }
        if (!InventorySimulator.fits(b.storage(), a.offeredItems(), s.inventoryMaxStack())) {
            return new Result(Reason.NO_SPACE, b.id());
        }
        Result money = checkMoney(s);
        if (money != null) {
            return money;
        }
        for (Trader t : new Trader[] {a, b}) {
            if (t.level() < t.offeredXp()) {
                return new Result(Reason.NO_XP, t.id());
            }
        }
        return Result.OK;
    }

    /** Only the net difference moves (3.5.1 step 4), so only the net payer needs the money. */
    private static Result checkMoney(CompletionSnapshot s) {
        BigDecimal aMoney = s.a().offeredMoney();
        BigDecimal bMoney = s.b().offeredMoney();
        if (aMoney.signum() == 0 && bMoney.signum() == 0) {
            return null;
        }
        if (s.economy() == null || !s.economy().isAvailable()) {
            return new Result(Reason.ECONOMY_UNAVAILABLE, null);
        }
        BigDecimal net = aMoney.subtract(bMoney);
        if (net.signum() == 0) {
            return null;
        }
        Trader payer = net.signum() > 0 ? s.a() : s.b();
        if (!s.economy().has(payer.id(), net.abs())) {
            return new Result(Reason.NO_MONEY, payer.id());
        }
        return null;
    }
}
