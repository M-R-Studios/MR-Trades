package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.trade.FakeEconomy.Mode;
import com.mrstudios.trades.trade.MoneyTransfer.Result;
import com.mrstudios.trades.trade.MoneyTransfer.Status;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Net transfers, rollback and rounding (DESIGN.md 3.5.1 step 4, 8.1 MoneyTransferTest). */
class MoneyTransferTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private FakeEconomy economy;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        economy.balances.put(a, new BigDecimal("500.00"));
        economy.balances.put(b, new BigDecimal("200.00"));
    }

    private Result transfer(String aOffer, String bOffer) {
        return MoneyTransfer.transfer(economy, a, new BigDecimal(aOffer), b, new BigDecimal(bOffer));
    }

    private void assertBalances(String aBalance, String bBalance) {
        assertEquals(0, new BigDecimal(aBalance).compareTo(economy.balanceOf(a)), "A's balance");
        assertEquals(0, new BigDecimal(bBalance).compareTo(economy.balanceOf(b)), "B's balance");
    }

    @Test
    void onlyTheNetDifferenceMovesInOneWithdrawAndOneDeposit() {
        Result result = transfer("100", "30");

        assertEquals(Status.OK, result.status());
        assertEquals(a, result.payer());
        assertEquals(0, new BigDecimal("70").compareTo(result.amount()));
        assertEquals(List.of("withdraw 70.00", "deposit 70.00"), economy.calls);
        assertBalances("430", "270");
    }

    @Test
    void netPayerCanBeB() {
        Result result = transfer("10", "60.50");
        assertEquals(b, result.payer());
        assertBalances("550.50", "149.50");
    }

    @Test
    void equalOffersMakeNoCalls() {
        assertEquals(Status.OK, transfer("50", "50.00").status());
        assertEquals(List.of(), economy.calls);
        assertBalances("500", "200");
    }

    @Test
    void zeroMoneyMakesNoCalls() {
        assertEquals(Status.OK, transfer("0", "0").status());
        assertEquals(List.of(), economy.calls);
    }

    @Test
    void failedWithdrawChangesNothing() {
        economy.plan.put(1, Mode.FAIL);
        Result result = transfer("100", "0");

        assertEquals(Status.WITHDRAW_FAILED, result.status());
        assertEquals(List.of("withdraw 100.00"), economy.calls);
        assertBalances("500", "200");
    }

    @Test
    void throwingWithdrawCountsAsFailed() {
        economy.plan.put(1, Mode.THROW);
        Result result = transfer("100", "0");
        assertEquals(Status.WITHDRAW_FAILED, result.status());
        assertTrue(result.errors().contains("threw"), result.errors());
        assertBalances("500", "200");
    }

    @Test
    void insufficientFundsIsAFailedWithdraw() {
        assertEquals(Status.WITHDRAW_FAILED, transfer("500.01", "0").status());
        assertBalances("500", "200");
    }

    @Test
    void failedDepositRefundsThePayer() {
        economy.plan.put(2, Mode.FAIL);
        Result result = transfer("100", "0");

        assertEquals(Status.DEPOSIT_FAILED, result.status());
        assertEquals(List.of("withdraw 100.00", "deposit 100.00", "deposit 100.00"), economy.calls);
        assertBalances("500", "200");
    }

    @Test
    void throwingDepositRefundsThePayer() {
        economy.plan.put(2, Mode.THROW);
        Result result = transfer("100", "0");

        assertEquals(Status.DEPOSIT_FAILED, result.status());
        assertTrue(result.errors().contains("threw"), result.errors());
        assertBalances("500", "200");
    }

    @Test
    void failedRefundIsAnEconomyErrorWithBothMessages() {
        economy.plan.put(2, Mode.FAIL);
        economy.plan.put(3, Mode.THROW);
        Result result = transfer("100", "0");

        assertEquals(Status.ECONOMY_ERROR, result.status());
        assertTrue(result.errors().contains("deposit refused"), result.errors());
        assertTrue(result.errors().contains("threw"), result.errors());
        assertEquals(3, economy.calls.size(), "nothing else is attempted");
        assertBalances("400", "200");
    }

    @ParameterizedTest
    @CsvSource({
        "2,  10.567, 10.56",
        "0,  10.9,   10",
        "-1, 3.14159, 3.14",
        "3,  1.23456, 1.234",
    })
    void roundsDownToTheEconomysDecimals(int digits, String amount, String expected) {
        economy.digits = digits;
        Result result = transfer(amount, "0");
        assertEquals(0, new BigDecimal(expected).compareTo(result.amount()), result.toString());
    }

    @Test
    void roundingCanCancelATinyDifference() {
        economy.digits = 0;
        assertEquals(Status.OK, transfer("10.4", "10").status());
        assertEquals(List.of(), economy.calls);
    }
}
