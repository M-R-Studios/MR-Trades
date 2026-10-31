package com.mrstudios.trades.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.mrstudios.trades.input.AmountParser.Invalid;
import com.mrstudios.trades.input.AmountParser.Result;
import com.mrstudios.trades.input.AmountParser.TooHigh;
import com.mrstudios.trades.input.AmountParser.Valid;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountParserTest {

    private static final BigDecimal BIG = new BigDecimal("1000000000");

    private static BigDecimal money(String input) {
        Result result = AmountParser.parseMoney(input, 2, BIG);
        return assertInstanceOf(Valid.class, result, input + " -> " + result).amount();
    }

    @ParameterizedTest
    @CsvSource({
        "250,        250",
        "0,          0",
        "'1,000',    1000",
        "1_000,      1000",
        "'10,000,000', 10000000",
        "1_000_000,  1000000",
        "2.5k,       2500",
        "2.5K,       2500",
        "1M,         1000000",
        "1m,         1000000",
        "12.34,      12.34",
        "12.30,      12.3",
        "0.01,       0.01",
        "1.005k,     1005",
        "'  42  ',   42",
        "007,        7",
        "'1,234.56', 1234.56",
    })
    void validMoney(String input, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(money(input)), input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5", "+5", "NaN", "Infinity", "-Infinity", "1e9", "1E3", "12.345", "", "   ",
        "abc", "5 coins", "$5", "2,5", "1,00", "1,0000", ",100", "100,", "1__000", "1.2.3", ".5", "5.", "0x10",
        "1kk", "k", "1b", "١٢٣", "1 000"})
    void invalidMoney(String input) {
        assertInstanceOf(Invalid.class, AmountParser.parseMoney(input, 2, BIG), input);
    }

    @Test
    void nullIsInvalid() {
        assertInstanceOf(Invalid.class, AmountParser.parseMoney(null, 2, BIG));
        assertInstanceOf(Invalid.class, AmountParser.parseXp(null, 30));
    }

    @Test
    void invalidEchoesTheTrimmedInput() {
        assertEquals(new Invalid("lots"), AmountParser.parseMoney("  lots ", 2, BIG));
    }

    @Test
    void decimalsFollowTheEconomy() {
        assertInstanceOf(Invalid.class, AmountParser.parseMoney("1.5", 0, BIG));
        assertEquals(new Valid(new BigDecimal("1500")), AmountParser.parseMoney("1.5k", 0, BIG),
                "a suffix can make a whole number");
        assertInstanceOf(Invalid.class, AmountParser.parseMoney("1.0005k", 0, BIG));
        assertEquals(new Valid(new BigDecimal("1.234")), AmountParser.parseMoney("1.234", 3, BIG));
        assertInstanceOf(Invalid.class, AmountParser.parseMoney("1.234", 2, BIG));
    }

    @Test
    void unknownFractionalDigitsAllowTwo() {
        assertEquals(new Valid(new BigDecimal("1.23")), AmountParser.parseMoney("1.23", -1, BIG));
        assertInstanceOf(Invalid.class, AmountParser.parseMoney("1.234", -1, BIG));
    }

    @Test
    void overTheMaximumIsTooHigh() {
        BigDecimal max = new BigDecimal("500.50");
        assertEquals(new TooHigh(max), AmountParser.parseMoney("500.51", 2, max));
        assertEquals(new TooHigh(max), AmountParser.parseMoney("1k", 2, max));
        assertEquals(new Valid(new BigDecimal("500.5")), AmountParser.parseMoney("500.50", 2, max));
        assertEquals(new TooHigh(BigDecimal.ZERO), AmountParser.parseMoney("1", 2, BigDecimal.ZERO));
        assertEquals(new Valid(BigDecimal.ZERO), AmountParser.parseMoney("0", 2, BigDecimal.ZERO));
    }

    @Test
    void hugeNumbersDontOverflow() {
        assertInstanceOf(TooHigh.class, AmountParser.parseMoney("99999999999999999999999999999m", 2, BIG));
        assertInstanceOf(TooHigh.class, AmountParser.parseXp("99999999999999999999", 30));
    }

    @Test
    void amountsAreNormalised() {
        assertEquals(new Valid(new BigDecimal("2500")), AmountParser.parseMoney("2.500k", 2, BIG));
        assertEquals(0, new Valid(new BigDecimal("2500")).amount().scale());
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "5, 5", "30, 30", "'1,000', 1000", "1_000, 1000"})
    void validXp(String input, int expected) {
        Result result = AmountParser.parseXp(input, 5000);
        assertEquals(expected, assertInstanceOf(Valid.class, result, input).amount().intValueExact());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "2k", "1m", "-1", "1e2", "NaN", "", "level 5"})
    void invalidXp(String input) {
        assertInstanceOf(Invalid.class, AmountParser.parseXp(input, 5000), input);
    }

    @Test
    void xpOverLevelsIsTooHigh() {
        assertEquals(new TooHigh(BigDecimal.valueOf(30)), AmountParser.parseXp("31", 30));
        assertEquals(new Valid(BigDecimal.valueOf(30)), AmountParser.parseXp("30", 30));
    }
}
