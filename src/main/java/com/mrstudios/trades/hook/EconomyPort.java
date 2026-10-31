package com.mrstudios.trades.hook;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The economy as the trade logic sees it, so that logic can be tested with a fake. The Vault
 * implementation arrives with money trading (DESIGN.md step 6), which also adds withdraw and
 * deposit.
 */
public interface EconomyPort {

    /** Whether an economy is installed and enabled right now. */
    boolean isAvailable();

    /** Whether {@code player} has at least {@code amount}. */
    boolean has(UUID player, BigDecimal amount);
}
