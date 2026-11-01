package com.mrstudios.trades.trade;

import static com.mrstudios.trades.trade.PlainStack.of;
import static com.mrstudios.trades.trade.PlainStack.pearls;
import static com.mrstudios.trades.trade.PlainStack.sword;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.Settings.Limits;
import com.mrstudios.trades.hook.EconomyPort;
import com.mrstudios.trades.rules.LimitsCheck;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import com.mrstudios.trades.trade.CompletionSnapshot.Trader;
import com.mrstudios.trades.trade.CompletionValidator.Reason;
import com.mrstudios.trades.trade.CompletionValidator.Result;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

/** One case per failure reason, plus the all-OK case (DESIGN.md 8.1). Plain values, no server. */
class CompletionValidatorTest {

    private static final long VERSION = 7;
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Limits LIMITS = Settings.DEFAULTS.limits();

    /** A builder for one trader, starting from a valid, confirmed, online survival player. */
    private static final class T {
        UUID id;
        boolean online = true;
        boolean dead;
        GameMode gameMode = GameMode.SURVIVAL;
        boolean bypassWorld;
        Position position;
        long confirmed = VERSION;
        int level = 30;
        List<StackView> storage = new ArrayList<>(Collections.nCopies(36, null));
        List<StackView> offered = new ArrayList<>();
        BigDecimal money = BigDecimal.ZERO;
        int xp;

        T(UUID id, double x) {
            this.id = id;
            this.position = new Position("world", x, 64, 0);
        }

        Trader build() {
            return new Trader(id, online, dead, gameMode, bypassWorld, position, confirmed, level, storage, offered,
                    money, xp);
        }
    }

    private final T a = new T(A, 0);
    private final T b = new T(B, 3);
    private final FakeEconomy economy = new FakeEconomy();
    private Limits limits = LIMITS;
    private boolean bypassDistance;
    private EconomyPort economyPort = economy;

    private Result validate() {
        return CompletionValidator.validate(
                new CompletionSnapshot(VERSION, a.build(), b.build(), limits, bypassDistance, economyPort, 64));
    }

    private void assertFails(Reason reason, UUID player) {
        Result result = validate();
        assertEquals(reason, result.reason(), result.toString());
        assertEquals(player, result.player());
        assertFalse(result.ok());
    }

    private static List<StackView> fullStorage() {
        return new ArrayList<>(Collections.nCopies(36, of("stone", 64)));
    }

    @Test
    void allGoodIsOk() {
        a.offered = List.of(of("diamond", 64), sword("Excalibur"));
        b.offered = List.of(pearls(16));
        a.money = BigDecimal.TEN;
        economy.balances.put(A, BigDecimal.TEN);
        a.xp = 5;
        b.xp = 30;

        assertTrue(validate().ok());
        assertEquals(Result.OK, validate());
    }

    @Test
    void emptyTradeIsOk() {
        assertTrue(validate().ok());
    }

    @Test
    void offlinePlayerAborts() {
        b.online = false;
        assertFails(Reason.PLAYER_OFFLINE, B);
        assertTrue(Reason.PLAYER_OFFLINE.abortsTrade());
    }

    @Test
    void missingPositionCountsAsOffline() {
        a.position = null;
        assertFails(Reason.PLAYER_OFFLINE, A);
    }

    @Test
    void deadPlayerAborts() {
        a.dead = true;
        assertFails(Reason.PLAYER_DEAD, A);
        assertTrue(Reason.PLAYER_DEAD.abortsTrade());
    }

    @Test
    void blockedGameModeAborts() {
        b.gameMode = GameMode.CREATIVE;
        assertFails(Reason.BLOCKED_GAMEMODE, B);
        assertTrue(Reason.BLOCKED_GAMEMODE.abortsTrade());

        b.gameMode = GameMode.SPECTATOR;
        assertFails(Reason.BLOCKED_GAMEMODE, B);
        b.gameMode = GameMode.ADVENTURE;
        assertTrue(validate().ok());
    }

    @Test
    void allowedCreativeWhenNotBlocked() {
        limits = new Limits(16, 10, false, Set.of(), Set.of());
        a.gameMode = GameMode.CREATIVE;
        assertTrue(validate().ok());
    }

    @Test
    void versionMismatchFailsButStaysOpen() {
        a.confirmed = VERSION - 1;
        assertFails(Reason.NOT_CONFIRMED, A);
        assertFalse(Reason.NOT_CONFIRMED.abortsTrade());
    }

    @Test
    void oneSideUnconfirmedFails() {
        b.confirmed = -1;
        assertFails(Reason.NOT_CONFIRMED, B);
    }

    @Test
    void tooFarAborts() {
        b.position = new Position("world", 16.01, 64, 0);
        assertFails(Reason.TOO_FAR, null);
        assertTrue(Reason.TOO_FAR.abortsTrade());

        b.position = new Position("world", 16, 64, 0);
        assertTrue(validate().ok(), "exactly at max-distance is allowed");
    }

    @Test
    void tooFarIsIgnoredWithBypass() {
        b.position = new Position("world", 1000, 64, 0);
        bypassDistance = true;
        assertTrue(validate().ok());
    }

    @Test
    void crossWorldAbortsUnlessAllowedOrBypassed() {
        b.position = new Position("world_nether", 0, 64, 0);
        assertFails(Reason.OTHER_WORLD, null);
        assertTrue(Reason.OTHER_WORLD.abortsTrade());

        limits = new Limits(16, 10, true, Set.of(), Set.of());
        assertTrue(validate().ok(), "cross-world allowed: distance across worlds isn't checked");

        limits = LIMITS;
        bypassDistance = true;
        assertTrue(validate().ok());
    }

    @Test
    void disabledWorldAbortsUnlessThatPlayerBypasses() {
        limits = new Limits(16, 10, false, Set.of("world"), Set.of());
        assertFails(Reason.DISABLED_WORLD, null);
        assertTrue(Reason.DISABLED_WORLD.abortsTrade());

        a.bypassWorld = true;
        assertFails(Reason.DISABLED_WORLD, null);
        b.bypassWorld = true;
        assertTrue(validate().ok());
    }

    @Test
    void noSpaceForAFailsButStaysOpen() {
        a.storage = fullStorage();
        b.offered = List.of(of("diamond", 1));
        assertFails(Reason.NO_SPACE, A);
        assertFalse(Reason.NO_SPACE.abortsTrade());
    }

    @Test
    void noSpaceForBFails() {
        b.storage = fullStorage();
        b.storage.set(0, null);
        a.offered = List.of(sword("x"), sword("y"));
        assertFails(Reason.NO_SPACE, B);
    }

    @Test
    void spaceCountsWhatTheReceiverAlsoGivesAwayAsStillThere() {
        // B's storage is full; giving items away doesn't free space before receiving (the
        // offered items already left the inventory into the session).
        b.storage = fullStorage();
        b.offered = List.of(of("dirt", 64));
        a.offered = List.of(of("diamond", 1));
        assertFails(Reason.NO_SPACE, B);
    }

    @Test
    void incomingThatMergesIntoPartialStacksFits() {
        a.storage = fullStorage();
        a.storage.set(4, pearls(10));
        b.offered = List.of(pearls(6));
        assertTrue(validate().ok());

        b.offered = List.of(pearls(7));
        assertFails(Reason.NO_SPACE, A);
    }

    @Test
    void moneyWithoutEconomyFails() {
        a.money = BigDecimal.ONE;
        economyPort = null;
        assertFails(Reason.ECONOMY_UNAVAILABLE, null);
        assertFalse(Reason.ECONOMY_UNAVAILABLE.abortsTrade());
    }

    @Test
    void moneyWithDisabledEconomyFails() {
        b.money = BigDecimal.ONE;
        economy.balances.put(B, BigDecimal.TEN);
        economy.available = false;
        assertFails(Reason.ECONOMY_UNAVAILABLE, null);
    }

    @Test
    void noMoneyOfferedNeedsNoEconomy() {
        economyPort = null;
        assertTrue(validate().ok());
    }

    @Test
    void onlyTheNetPayerNeedsTheNetAmount() {
        a.money = new BigDecimal("100");
        b.money = new BigDecimal("30");
        economy.balances.put(A, new BigDecimal("70"));
        assertTrue(validate().ok(), "A pays B 70; A needn't hold the full 100");

        economy.balances.put(A, new BigDecimal("69.99"));
        assertFails(Reason.NO_MONEY, A);
        assertFalse(Reason.NO_MONEY.abortsTrade());
    }

    @Test
    void netPayerCanBeB() {
        a.money = new BigDecimal("5");
        b.money = new BigDecimal("12.50");
        economy.balances.put(B, new BigDecimal("7.49"));
        assertFails(Reason.NO_MONEY, B);
        economy.balances.put(B, new BigDecimal("7.50"));
        assertTrue(validate().ok());
    }

    @Test
    void equalMoneyOffersMoveNothingButStillNeedTheEconomy() {
        a.money = new BigDecimal("50");
        b.money = new BigDecimal("50.00");
        assertTrue(validate().ok(), "nobody needs a balance when nothing moves");
        economy.available = false;
        assertFails(Reason.ECONOMY_UNAVAILABLE, null);
    }

    @Test
    void notEnoughLevelsFailsButStaysOpen() {
        b.xp = 31;
        assertFails(Reason.NO_XP, B);
        assertFalse(Reason.NO_XP.abortsTrade());
        b.xp = 30;
        assertTrue(validate().ok());
    }

    @Test
    void abortingReasonsWinOverOpenOnes() {
        a.storage = fullStorage();
        b.offered = List.of(of("diamond", 1));
        a.xp = 99;
        b.online = false;
        assertFails(Reason.PLAYER_OFFLINE, B);
    }

    @Test
    void limitsCheckDirectly() {
        Position here = new Position("World", 0, 0, 0);
        Limits noLimit = new Limits(0, 10, false, Set.of(), Set.of());
        assertNull(LimitsCheck.check(noLimit, here, new Position("World", 1e6, 0, 0), false, false, false));
        assertEquals(LimitsCheck.Failure.TOO_FAR,
                LimitsCheck.check(LIMITS, here, new Position("World", 10, 10, 10), false, false, false),
                "distance is 3D");
        Limits disabled = new Limits(16, 10, false, Set.of("world"), Set.of());
        assertEquals(LimitsCheck.Failure.DISABLED_WORLD,
                LimitsCheck.check(disabled, here, here, true, false, false),
                "world names are case-insensitive, and distance bypass doesn't bypass disabled worlds");
    }
}
