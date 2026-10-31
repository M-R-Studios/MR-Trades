package com.mrstudios.trades.request;

import com.mrstudios.trades.config.Settings.Limits;
import com.mrstudios.trades.rules.LimitsCheck;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;

/**
 * Whether one player may send a trade request to another, or accept one (DESIGN.md 3.8: requests
 * are checked again on accept). Pure: the caller copies the players' state into {@link Party}.
 */
public final class RequestChecks {

    /**
     * One player as the checks see them.
     *
     * @param canTrade has {@code mrtrades.trade}
     * @param requestsOff turned requests off with {@code /trade toggle}
     * @param busy already in a trade
     */
    public record Party(UUID id, String name, Position position, GameMode gameMode, boolean canTrade,
            boolean bypassDistance, boolean bypassWorld, boolean bypassToggle, boolean requestsOff, boolean busy) {
    }

    /**
     * Why it's refused: a {@code messages.yml} key and its placeholders, shown to the player who
     * acted.
     */
    public record Denial(String messageKey, Map<String, String> placeholders) {

        static Denial of(String key) {
            return new Denial(key, Map.of());
        }

        static Denial of(String key, String name, String value) {
            return new Denial(key, Map.of(name, value));
        }
    }

    private RequestChecks() {
    }

    /** Checks for {@code /trade <target>}. The cooldown is checked separately. */
    public static Denial checkSend(Party sender, Party target, Limits limits) {
        Denial common = checkCommon(sender, target, limits);
        if (common != null) {
            return common;
        }
        if (!target.canTrade() || (target.requestsOff() && !sender.bypassToggle())) {
            return Denial.of("target-toggled", "player", target.name());
        }
        return null;
    }

    /** Checks for accepting a request from {@code requester}. */
    public static Denial checkAccept(Party accepter, Party requester, Limits limits) {
        Denial common = checkCommon(accepter, requester, limits);
        if (common != null) {
            return common;
        }
        if (!requester.canTrade()) {
            return Denial.of("target-toggled", "player", requester.name());
        }
        return null;
    }

    /** The rules that apply both ways; messages are worded for {@code actor}. */
    private static Denial checkCommon(Party actor, Party other, Limits limits) {
        if (actor.id().equals(other.id())) {
            return Denial.of("target-self");
        }
        if (actor.busy()) {
            return Denial.of("self-busy");
        }
        if (other.busy()) {
            return Denial.of("target-busy", "player", other.name());
        }
        if (limits.blockedGameModes().contains(actor.gameMode())) {
            return Denial.of("blocked-gamemode", "gamemode", actor.gameMode().name().toLowerCase(Locale.ROOT));
        }
        if (limits.blockedGameModes().contains(other.gameMode())) {
            return Denial.of("target-blocked-gamemode", "player", other.name());
        }
        LimitsCheck.Failure failure = LimitsCheck.check(limits, actor.position(), other.position(),
                actor.bypassDistance() || other.bypassDistance(), actor.bypassWorld(), other.bypassWorld());
        if (failure == null) {
            return null;
        }
        return switch (failure) {
            case DISABLED_WORLD -> Denial.of("disabled-world");
            case OTHER_WORLD -> Denial.of("other-world", "player", other.name());
            case TOO_FAR -> new Denial("too-far",
                    Map.of("player", other.name(), "distance", String.valueOf(limits.maxDistance())));
        };
    }
}
