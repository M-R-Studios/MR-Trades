package com.mrstudios.trades.rules;

import com.mrstudios.trades.config.Settings.Limits;

/**
 * The distance, cross-world and disabled-world rules (DESIGN.md 3.5 #15). Used when a request is
 * sent and accepted, on the timer during a trade and again at completion. Movement itself is
 * never checked; only the resulting positions are.
 */
public final class LimitsCheck {

    /** Where a player is. */
    public record Position(String world, double x, double y, double z) {
    }

    public enum Failure {
        /** One of the players is in a world listed in {@code limits.disabled-worlds}. */
        DISABLED_WORLD,
        /** The players are in different worlds and cross-world trading is off. */
        OTHER_WORLD,
        /** Further apart than {@code limits.max-distance}. */
        TOO_FAR
    }

    private LimitsCheck() {
    }

    /**
     * @param bypassDistance either player has {@code mrtrades.bypass.distance}
     * @param aBypassWorld player A has {@code mrtrades.bypass.world}
     * @param bBypassWorld player B has {@code mrtrades.bypass.world}
     * @return the first rule broken, or null if trading is allowed
     */
    public static Failure check(Limits limits, Position a, Position b, boolean bypassDistance,
            boolean aBypassWorld, boolean bBypassWorld) {
        if ((limits.isWorldDisabled(a.world()) && !aBypassWorld)
                || (limits.isWorldDisabled(b.world()) && !bBypassWorld)) {
            return Failure.DISABLED_WORLD;
        }
        if (bypassDistance) {
            return null;
        }
        if (!a.world().equals(b.world())) {
            // Distance means nothing across worlds; cross-world trades are allowed or not.
            return limits.allowCrossWorld() ? null : Failure.OTHER_WORLD;
        }
        if (limits.maxDistance() > 0) {
            double dx = a.x() - b.x();
            double dy = a.y() - b.y();
            double dz = a.z() - b.z();
            double max = limits.maxDistance();
            if (dx * dx + dy * dy + dz * dz > max * max) {
                return Failure.TOO_FAR;
            }
        }
        return null;
    }
}
