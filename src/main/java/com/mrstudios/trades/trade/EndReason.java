package com.mrstudios.trades.trade;

/** Why a trade ended without completing. Each maps to a {@code reason-*} message. */
public enum EndReason {
    /** A player closed the window (Esc, or another plugin closed it). */
    CLOSED("reason-closed"),
    /** A player pressed cancel or ran {@code /trade cancel}. */
    CANCELLED("reason-cancelled"),
    QUIT("reason-quit"),
    DIED("reason-died"),
    TOO_FAR("reason-too-far"),
    WORLD("reason-world"),
    GAMEMODE("reason-gamemode"),
    SERVER_STOP("reason-server-stop"),
    ADMIN("reason-admin"),
    /** The window disappeared without a close event (another plugin swapped the view). */
    LOST_VIEW("reason-lost-view");

    private final String messageKey;

    EndReason(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
