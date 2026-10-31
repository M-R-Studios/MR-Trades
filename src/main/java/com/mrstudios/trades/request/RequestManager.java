package com.mrstudios.trades.request;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The pending trade requests. Expired requests are invisible from the moment they expire, even
 * before {@link #expire()} removes them and the players are told. Main thread only.
 */
public final class RequestManager {

    private final LongSupplier clock;
    private final List<TradeRequest> requests = new ArrayList<>();

    /** @param clock epoch millis */
    public RequestManager(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records a request from {@code from} to {@code to}.
     *
     * @return the new request, or empty if {@code from} already has a live request to {@code to}
     */
    public Optional<TradeRequest> send(UUID from, String fromName, UUID to, String toName, long timeoutMs) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("A player can't request themselves");
        }
        if (find(from, to).isPresent()) {
            return Optional.empty();
        }
        // An expired request between the same two players is replaced.
        requests.removeIf(r -> r.from().equals(from) && r.to().equals(to));
        long now = clock.getAsLong();
        TradeRequest request = new TradeRequest(from, fromName, to, toName, now, now + timeoutMs);
        requests.add(request);
        return Optional.of(request);
    }

    /** The live request from {@code from} to {@code to}. */
    public Optional<TradeRequest> find(UUID from, UUID to) {
        long now = clock.getAsLong();
        return requests.stream()
                .filter(r -> r.from().equals(from) && r.to().equals(to) && !r.isExpired(now))
                .findFirst();
    }

    /** The most recent live request {@code to} received. */
    public Optional<TradeRequest> latestTo(UUID to) {
        return incoming(to).stream().max(Comparator.comparingLong(TradeRequest::sentAt));
    }

    /** Live requests {@code to} received, oldest first. */
    public List<TradeRequest> incoming(UUID to) {
        long now = clock.getAsLong();
        return requests.stream().filter(r -> r.to().equals(to) && !r.isExpired(now)).toList();
    }

    /** Live requests {@code from} sent, oldest first. */
    public List<TradeRequest> outgoing(UUID from) {
        long now = clock.getAsLong();
        return requests.stream().filter(r -> r.from().equals(from) && !r.isExpired(now)).toList();
    }

    /** @return false if it was already gone (answered, withdrawn or expired and removed) */
    public boolean remove(TradeRequest request) {
        return requests.remove(request);
    }

    /** Removes every request {@code player} sent or received (quit, or a trade started). */
    public List<TradeRequest> removeAllOf(UUID player) {
        List<TradeRequest> removed = new ArrayList<>();
        for (Iterator<TradeRequest> it = requests.iterator(); it.hasNext(); ) {
            TradeRequest request = it.next();
            if (request.involves(player)) {
                removed.add(request);
                it.remove();
            }
        }
        return removed;
    }

    /** Removes the requests that have expired and returns them, so both players can be told. */
    public List<TradeRequest> expire() {
        long now = clock.getAsLong();
        List<TradeRequest> expired = new ArrayList<>();
        for (Iterator<TradeRequest> it = requests.iterator(); it.hasNext(); ) {
            TradeRequest request = it.next();
            if (request.isExpired(now)) {
                expired.add(request);
                it.remove();
            }
        }
        return expired;
    }

    public int size() {
        return requests.size();
    }
}
