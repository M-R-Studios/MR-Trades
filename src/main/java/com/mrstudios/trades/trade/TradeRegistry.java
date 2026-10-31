package com.mrstudios.trades.trade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which player is in which trade. The only place trade state is looked up from, so nothing about
 * a trade is left on a player once it's removed here. A player is in at most one trade. Main
 * thread only.
 */
public final class TradeRegistry {

    private final Map<UUID, TradeSession> byPlayer = new HashMap<>();

    /**
     * Adds both players of {@code session}.
     *
     * @return false (and nothing added) if either player is already trading or the session has ended
     */
    public boolean register(TradeSession session) {
        UUID a = session.a().playerId();
        UUID b = session.b().playerId();
        if (session.isEnded() || byPlayer.containsKey(a) || byPlayer.containsKey(b)) {
            return false;
        }
        byPlayer.put(a, session);
        byPlayer.put(b, session);
        return true;
    }

    /** The trade {@code player} is in, or null. */
    public TradeSession get(UUID player) {
        return byPlayer.get(player);
    }

    public boolean isTrading(UUID player) {
        return byPlayer.containsKey(player);
    }

    /** Removes both players, but only where they still point at {@code session}. */
    public void remove(TradeSession session) {
        byPlayer.remove(session.a().playerId(), session);
        byPlayer.remove(session.b().playerId(), session);
    }

    /** Every registered trade, once each. */
    public Collection<TradeSession> sessions() {
        return List.copyOf(new LinkedHashSet<>(byPlayer.values()));
    }

    public boolean isEmpty() {
        return byPlayer.isEmpty();
    }

    /**
     * Self-heal for the watchdog (DESIGN.md 3.7): removes every player still mapped to an ended
     * trade, which should never happen.
     *
     * @return the players that were removed, so the caller can log them
     */
    public List<UUID> removeEnded() {
        List<UUID> removed = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, TradeSession>> it = byPlayer.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, TradeSession> entry = it.next();
            if (entry.getValue().isEnded()) {
                removed.add(entry.getKey());
                it.remove();
            }
        }
        return removed;
    }
}
