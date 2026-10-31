package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeRegistryTest {

    private final TradeRegistry registry = new TradeRegistry();
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final UUID kim = UUID.randomUUID();

    private static TradeSession session(UUID a, UUID b) {
        return new TradeSession(UUID.randomUUID(), a, "a", b, "b", () -> 0L);
    }

    @Test
    void registersBothPlayers() {
        TradeSession trade = session(alex, sam);

        assertTrue(registry.register(trade));
        assertSame(trade, registry.get(alex));
        assertSame(trade, registry.get(sam));
        assertTrue(registry.isTrading(alex));
        assertFalse(registry.isTrading(kim));
        assertEquals(List.of(trade), List.copyOf(registry.sessions()));
    }

    @Test
    void aPlayerIsInAtMostOneTrade() {
        TradeSession first = session(alex, sam);
        registry.register(first);

        assertFalse(registry.register(session(sam, kim)));
        assertFalse(registry.register(session(kim, alex)));
        assertFalse(registry.isTrading(kim), "a refused registration adds nobody");
        assertSame(first, registry.get(sam));
    }

    @Test
    void endedSessionCantBeRegistered() {
        TradeSession trade = session(alex, sam);
        trade.end();
        assertFalse(registry.register(trade));
        assertTrue(registry.isEmpty());
    }

    @Test
    void removeOnlyRemovesEntriesOfThatSession() {
        TradeSession old = session(alex, sam);
        registry.register(old);
        registry.remove(old);
        TradeSession next = session(alex, kim);
        registry.register(next);

        registry.remove(old);

        assertSame(next, registry.get(alex), "removing a stale session must not drop the new one");
        assertNull(registry.get(sam));
        registry.remove(next);
        assertTrue(registry.isEmpty());
    }

    @Test
    void removeEndedSelfHeals() {
        TradeSession live = session(alex, sam);
        TradeSession dead = session(kim, UUID.randomUUID());
        registry.register(live);
        registry.register(dead);
        dead.end();

        List<UUID> removed = registry.removeEnded();

        assertEquals(2, removed.size());
        assertTrue(removed.contains(kim));
        assertFalse(registry.isTrading(kim));
        assertTrue(registry.isTrading(alex));
        assertEquals(List.of(), registry.removeEnded());
    }
}
