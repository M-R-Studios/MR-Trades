package com.mrstudios.trades.input;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an amount typed in chat or a Bedrock form (DESIGN.md 4.2). Accepts digits with an
 * optional decimal part, {@code ,} or {@code _} as thousands separators ({@code 1,000},
 * {@code 1_000_000}) and, for money, the suffixes {@code k} and {@code m}. Rejects negatives,
 * {@code NaN}, {@code Infinity}, exponents, more decimals than allowed and anything over the
 * maximum. Separators must group exactly three digits, so {@code 2,5} (a decimal comma) is
 * rejected rather than read as 25.
 */
public final class AmountParser {

    /** Integer part (plain, or grouped by threes), optional decimals, optional suffix. */
    private static final Pattern AMOUNT =
            Pattern.compile("(\\d{1,3}(?:[,_]\\d{3})+|\\d+)(?:\\.(\\d+))?([km])?");

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** What came out of the input. */
    public sealed interface Result {
    }

    /** A valid amount, at most the maximum. */
    public record Valid(BigDecimal amount) implements Result {
    }

    /** Not an amount at all (the input is echoed back to the player). */
    public record Invalid(String input) implements Result {
    }

    /** A valid amount, but more than {@code max}. */
    public record TooHigh(BigDecimal max) implements Result {
    }

    private AmountParser() {
    }

    /**
     * @param fractionalDigits decimals the economy supports; negative means "unknown" and allows 2
     * @param max the most the player can offer (their balance, capped by {@code money.max-amount})
     */
    public static Result parseMoney(String input, int fractionalDigits, BigDecimal max) {
        return parse(input, fractionalDigits < 0 ? 2 : fractionalDigits, true, max);
    }

    /** Whole levels only, no suffixes. {@link Valid#amount()} fits in an int. */
    public static Result parseXp(String input, int maxLevels) {
        return parse(input, 0, false, BigDecimal.valueOf(maxLevels));
    }

    private static Result parse(String input, int decimals, boolean allowSuffix, BigDecimal max) {
        if (input == null) {
            return new Invalid("");
        }
        String text = input.strip();
        Matcher m = AMOUNT.matcher(text.toLowerCase(Locale.ROOT));
        // XP takes whole levels typed as such: "1.0" is rejected too, not read as 1.
        if (!m.matches() || (!allowSuffix && (m.group(2) != null || m.group(3) != null))) {
            return new Invalid(text);
        }
        String digits = m.group(1).replace(",", "").replace("_", "");
        BigDecimal amount = new BigDecimal(m.group(2) == null ? digits : digits + "." + m.group(2));
        if (m.group(3) != null) {
            amount = amount.multiply(m.group(3).equals("k") ? THOUSAND : MILLION);
        }
        amount = amount.stripTrailingZeros();
        if (amount.scale() < 0) {
            amount = amount.setScale(0);
        }
        if (amount.scale() > decimals) {
            return new Invalid(text);
        }
        if (amount.compareTo(max) > 0) {
            return new TooHigh(max);
        }
        return new Valid(amount);
    }
}
