package com.mrstudios.trades.hook;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The economy as the trade logic sees it, so that logic can be tested with a fake. Amounts are
 * {@link BigDecimal}; only the Vault implementation converts them to {@code double}.
 */
public interface EconomyPort {

    /** The outcome of a withdraw or deposit. {@code error} explains a failure, as the economy reported it. */
    record Response(boolean success, String error) {

        public static Response ok() {
            return new Response(true, null);
        }

        public static Response failed(String error) {
            return new Response(false, error);
        }
    }

    /** Whether an economy is installed and enabled right now. */
    boolean isAvailable();

    /** Whether {@code player} has at least {@code amount}. */
    boolean has(UUID player, BigDecimal amount);

    BigDecimal balance(UUID player);

    Response withdraw(UUID player, BigDecimal amount);

    Response deposit(UUID player, BigDecimal amount);

    /** Decimal places the economy supports; -1 when it doesn't say. */
    int fractionalDigits();

    /** {@code amount} written the way the economy writes money (e.g. "$1,250.00"). */
    String format(BigDecimal amount);

    /** The economy plugin's name, for {@code /mrtrades version}. */
    String name();
}
