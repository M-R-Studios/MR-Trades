package com.mrstudios.trades.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pending requests with a fake clock (DESIGN.md 8.1 RequestManagerTest). */
class RequestManagerTest {

    private static final long TIMEOUT = 30_000;

    private long now = 1_000_000;
    private final RequestManager manager = new RequestManager(() -> now);
    private final CooldownTracker cooldowns = new CooldownTracker(() -> now);
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();
    private final UUID kim = UUID.randomUUID();

    private TradeRequest send(UUID from, UUID to) {
        return manager.send(from, name(from), to, name(to), TIMEOUT).orElseThrow();
    }

    private String name(UUID id) {
        return id.equals(alex) ? "Alex" : id.equals(sam) ? "Sam" : "Kim";
    }

    @Test
    void sendRecordsTheRequest() {
        TradeRequest request = send(alex, sam);

        assertEquals(now, request.sentAt());
        assertEquals(now + TIMEOUT, request.expiresAt());
        assertEquals(request, manager.find(alex, sam).orElseThrow());
        assertEquals(List.of(request), manager.incoming(sam));
        assertEquals(List.of(request), manager.outgoing(alex));
        assertTrue(manager.find(sam, alex).isEmpty(), "requests have a direction");
    }

    @Test
    void expiresExactlyAtTheTimeout() {
        TradeRequest request = send(alex, sam);

        now += TIMEOUT - 1;
        assertTrue(manager.find(alex, sam).isPresent());
        assertEquals(List.of(), manager.expire());

        now += 1;
        assertTrue(manager.find(alex, sam).isEmpty(), "invisible from the moment it expires");
        assertTrue(manager.incoming(sam).isEmpty());
        assertTrue(manager.latestTo(sam).isEmpty());
        assertEquals(List.of(request), manager.expire());
        assertEquals(List.of(), manager.expire(), "reported once");
        assertEquals(0, manager.size());
    }

    @Test
    void duplicateRequestIsRefused() {
        send(alex, sam);
        assertTrue(manager.send(alex, "Alex", sam, "Sam", TIMEOUT).isEmpty());
        assertEquals(1, manager.size());
    }

    @Test
    void expiredRequestCanBeSentAgainAndIsReplaced() {
        send(alex, sam);
        now += TIMEOUT;
        TradeRequest again = send(alex, sam);

        assertEquals(1, manager.size(), "the expired one was replaced, not reported later");
        assertEquals(List.of(), manager.expire());
        assertEquals(again, manager.find(alex, sam).orElseThrow());
    }

    @Test
    void mutualRequestIsVisibleToTheSecondSender() {
        // RequestService turns "/trade Alex" by Sam into accepting Alex's request when this is present.
        send(alex, sam);
        assertTrue(manager.find(alex, sam).isPresent());
        assertEquals(alex, manager.latestTo(sam).orElseThrow().from());
    }

    @Test
    void latestIsTheMostRecentLiveRequest() {
        send(alex, kim);
        now += 10;
        TradeRequest fromSam = send(sam, kim);

        assertEquals(fromSam, manager.latestTo(kim).orElseThrow());
        now += TIMEOUT - 5;
        assertEquals(fromSam, manager.latestTo(kim).orElseThrow(), "Alex's expired first");
        assertEquals(1, manager.incoming(kim).size());
    }

    @Test
    void removeIsIdempotent() {
        TradeRequest request = send(alex, sam);
        assertTrue(manager.remove(request));
        assertFalse(manager.remove(request));
    }

    @Test
    void quittingRemovesThePlayersRequestsBothWays() {
        send(alex, sam);
        send(kim, alex);
        TradeRequest unrelated = send(sam, kim);

        List<TradeRequest> removed = manager.removeAllOf(alex);

        assertEquals(2, removed.size());
        assertTrue(manager.outgoing(alex).isEmpty());
        assertTrue(manager.incoming(alex).isEmpty());
        assertEquals(List.of(unrelated), manager.incoming(kim));
    }

    @Test
    void selfRequestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.send(alex, "Alex", alex, "Alex", TIMEOUT));
    }

    @Test
    void cooldownCountsDownAndRoundsUp() {
        cooldowns.sent(alex);

        assertEquals(5000, cooldowns.remainingMs(alex, 5000));
        assertEquals(5, cooldowns.remainingSeconds(alex, 5000));
        now += 4001;
        assertEquals(1, cooldowns.remainingSeconds(alex, 5000), "999 ms left still says 1s, never 0s");
        now += 999;
        assertEquals(0, cooldowns.remainingMs(alex, 5000));
        assertEquals(0, cooldowns.remainingMs(sam, 5000), "per player");
    }

    @Test
    void zeroCooldownIsOff() {
        cooldowns.sent(alex);
        assertEquals(0, cooldowns.remainingMs(alex, 0));
    }

    @Test
    void cooldownRestartsWithEachRequest() {
        cooldowns.sent(alex);
        now += 4000;
        cooldowns.sent(alex);
        assertEquals(5000, cooldowns.remainingMs(alex, 5000));
    }
}
