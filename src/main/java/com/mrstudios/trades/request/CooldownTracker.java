package com.mrstudios.trades.request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * How long a player must still wait before sending another trade request. Kept across rejoins, so
 * relogging doesn't skip the cooldown. Main thread only.
 */
public final class CooldownTracker {

    private final LongSupplier clock;
    private final Map<UUID, Long> lastSent = new HashMap<>();

    /** @param clock epoch millis */
    public CooldownTracker(LongSupplier clock) {
        this.clock = clock;
    }

    /** Records that {@code player} just sent a request. */
    public void sent(UUID player) {
        lastSent.put(player, clock.getAsLong());
    }

    /** Milliseconds left before {@code player} may send again, or 0. */
    public long remainingMs(UUID player, long cooldownMs) {
        Long last = lastSent.get(player);
        if (last == null || cooldownMs <= 0) {
            return 0;
        }
        return Math.max(0, last + cooldownMs - clock.getAsLong());
    }

    /** Whole seconds left, rounded up (so "wait 1s" never shows 0). */
    public long remainingSeconds(UUID player, long cooldownMs) {
        return (remainingMs(player, cooldownMs) + 999) / 1000;
    }
}
