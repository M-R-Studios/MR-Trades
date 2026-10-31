package com.mrstudios.trades.request;

import java.util.UUID;

/**
 * A trade request from one player to another, waiting for an answer.
 *
 * @param sentAt epoch millis
 * @param expiresAt epoch millis; the request is gone from this moment on
 */
public record TradeRequest(UUID from, String fromName, UUID to, String toName, long sentAt, long expiresAt) {

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }

    /** Whether {@code player} sent or received this request. */
    public boolean involves(UUID player) {
        return from.equals(player) || to.equals(player);
    }
}
