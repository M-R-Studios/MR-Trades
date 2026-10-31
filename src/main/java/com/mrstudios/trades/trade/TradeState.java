package com.mrstudios.trades.trade;

/** Where a {@link TradeSession} is in its life. Only moves forward to {@link #ENDED}. */
public enum TradeState {
    /** Both players can change their offers and confirm. */
    OPEN,
    /** Both confirmed the current offers; the countdown runs. Any change goes back to {@link #OPEN}. */
    COUNTDOWN,
    /** The swap is running. Nothing can change the offers. */
    COMPLETING,
    /** Completed or aborted. Final. */
    ENDED
}
