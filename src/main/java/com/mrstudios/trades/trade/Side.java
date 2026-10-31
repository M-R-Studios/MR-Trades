package com.mrstudios.trades.trade;

import java.util.Objects;
import java.util.UUID;

/** One trader in a {@link TradeSession}: who they are, their offer and their confirmation. */
public final class Side {

    static final long NOT_CONFIRMED = -1;

    private final UUID playerId;
    private final String name;
    private final Offer offer = new Offer();
    private long confirmedAtVersion = NOT_CONFIRMED;
    private long lockedUntilMillis;
    private ViewState view = ViewState.TRADE;

    Side(UUID playerId, String name) {
        this.playerId = Objects.requireNonNull(playerId);
        this.name = Objects.requireNonNull(name);
    }

    public UUID playerId() {
        return playerId;
    }

    /** The player's name when the trade started. */
    public String name() {
        return name;
    }

    /** Read-only from outside the package: change it through {@link TradeSession#changeOffer}. */
    public Offer offer() {
        return offer;
    }

    /** The session version this side confirmed, or -1. */
    public long confirmedAtVersion() {
        return confirmedAtVersion;
    }

    /** Until when (epoch millis) this side can't confirm because the other side just changed its offer. */
    public long lockedUntilMillis() {
        return lockedUntilMillis;
    }

    public ViewState view() {
        return view;
    }

    public void setView(ViewState view) {
        this.view = Objects.requireNonNull(view);
    }

    void confirmAt(long version) {
        confirmedAtVersion = version;
    }

    void clearConfirmation() {
        confirmedAtVersion = NOT_CONFIRMED;
    }

    void lockUntil(long millis) {
        lockedUntilMillis = millis;
    }
}
