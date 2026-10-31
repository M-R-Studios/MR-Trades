package com.mrstudios.trades.trade;

/**
 * What a trader is looking at. Set before the plugin closes or switches a view, so the close
 * handler can tell a close the plugin caused from one the player caused.
 */
public enum ViewState {
    TRADE,
    EDITOR,
    INPUT_CHAT,
    INPUT_FORM,
    REOPENING
}
