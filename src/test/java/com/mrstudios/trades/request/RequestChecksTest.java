package com.mrstudios.trades.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.Settings.Limits;
import com.mrstudios.trades.request.RequestChecks.Denial;
import com.mrstudios.trades.request.RequestChecks.Party;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class RequestChecksTest {

    private static final Limits LIMITS = Settings.DEFAULTS.limits();

    /** A mutable builder for a Party that may trade with anyone nearby. */
    private static final class P {
        UUID id = UUID.randomUUID();
        String name;
        Position position;
        GameMode gameMode = GameMode.SURVIVAL;
        boolean canTrade = true;
        boolean bypassDistance;
        boolean bypassWorld;
        boolean bypassToggle;
        boolean requestsOff;
        boolean busy;

        P(String name, double x) {
            this.name = name;
            this.position = new Position("world", x, 64, 0);
        }

        Party build() {
            return new Party(id, name, position, gameMode, canTrade, bypassDistance, bypassWorld, bypassToggle,
                    requestsOff, busy);
        }
    }

    private final P alex = new P("Alex", 0);
    private final P sam = new P("Sam", 5);
    private Limits limits = LIMITS;

    private Denial send() {
        return RequestChecks.checkSend(alex.build(), sam.build(), limits);
    }

    private Denial accept() {
        return RequestChecks.checkAccept(alex.build(), sam.build(), limits);
    }

    @Test
    void allowedByDefault() {
        assertNull(send());
        assertNull(accept());
    }

    @Test
    void self() {
        sam.id = alex.id;
        assertEquals(new Denial("target-self", Map.of()), send());
    }

    @Test
    void busyPlayers() {
        alex.busy = true;
        assertEquals(new Denial("self-busy", Map.of()), send());
        alex.busy = false;
        sam.busy = true;
        assertEquals(new Denial("target-busy", Map.of("player", "Sam")), send());
        assertEquals(new Denial("target-busy", Map.of("player", "Sam")), accept());
    }

    @Test
    void creativeAndSpectatorAreBlockedByDefault() {
        alex.gameMode = GameMode.CREATIVE;
        assertEquals(new Denial("blocked-gamemode", Map.of("gamemode", "creative")), send());
        alex.gameMode = GameMode.SURVIVAL;
        sam.gameMode = GameMode.SPECTATOR;
        assertEquals(new Denial("target-blocked-gamemode", Map.of("player", "Sam")), send());
        assertEquals(new Denial("target-blocked-gamemode", Map.of("player", "Sam")), accept());
        sam.gameMode = GameMode.ADVENTURE;
        assertNull(send());
    }

    @Test
    void tooFarUnlessEitherBypasses() {
        sam.position = new Position("world", 16.5, 64, 0);
        assertEquals(new Denial("too-far", Map.of("player", "Sam", "distance", "16")), send());
        assertEquals(new Denial("too-far", Map.of("player", "Sam", "distance", "16")), accept(),
                "checked again on accept");
        sam.bypassDistance = true;
        assertNull(send());
        sam.bypassDistance = false;
        alex.bypassDistance = true;
        assertNull(send());
    }

    @Test
    void otherWorld() {
        sam.position = new Position("world_nether", 0, 64, 0);
        assertEquals(new Denial("other-world", Map.of("player", "Sam")), send());
        limits = new Limits(16, 10, true, Set.of(), LIMITS.blockedGameModes());
        assertNull(send());
    }

    @Test
    void disabledWorldOfEitherPlayer() {
        limits = new Limits(16, 10, false, Set.of("world"), LIMITS.blockedGameModes());
        assertEquals(new Denial("disabled-world", Map.of()), send());
        alex.bypassWorld = true;
        assertEquals(new Denial("disabled-world", Map.of()), send(), "Sam is in a disabled world too");
        sam.bypassWorld = true;
        assertNull(send());
    }

    @Test
    void toggledOffUnlessBypassed() {
        sam.requestsOff = true;
        assertEquals(new Denial("target-toggled", Map.of("player", "Sam")), send());
        alex.bypassToggle = true;
        assertNull(send());
    }

    @Test
    void toggleDoesNotAffectAccepting() {
        // Sam turned requests off after sending one; Alex can still accept it.
        sam.requestsOff = true;
        assertNull(accept());
    }

    @Test
    void targetWithoutTradePermission() {
        sam.canTrade = false;
        assertEquals(new Denial("target-toggled", Map.of("player", "Sam")), send());
        assertEquals(new Denial("target-toggled", Map.of("player", "Sam")), accept());
    }
}
