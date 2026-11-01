package com.mrstudios.trades.trade;

import com.mrstudios.trades.hook.EconomyPort;
import com.mrstudios.trades.hook.EconomyPort.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Step 4 of atomic completion (DESIGN.md 3.5.1): moves money between the two traders. Only the
 * net difference moves (A offers 100, B offers 30: A pays B 70), so it's one withdraw and one
 * deposit. If the deposit fails, the withdrawal is refunded. Any exception from the economy counts
 * as a failure, because it's unknown whether a thrown call applied.
 */
public final class MoneyTransfer {

    public enum Status {
        /** Done, or nothing to move. */
        OK,
        /** The withdrawal failed: nothing changed. */
        WITHDRAW_FAILED,
        /** The deposit failed and the payer was refunded: nothing changed. */
        DEPOSIT_FAILED,
        /** The deposit and the refund both failed: the payer lost the amount. An admin must fix it. */
        ECONOMY_ERROR
    }

    /**
     * @param payer who paid (null when nothing moved)
     * @param amount the net amount
     * @param errors what the economy said, for the log
     */
    public record Result(Status status, UUID payer, UUID payee, BigDecimal amount, String errors) {

        public boolean ok() {
            return status == Status.OK;
        }
    }

    private MoneyTransfer() {
    }

    /** {@code amount} rounded down to what the economy supports (2 decimals when it doesn't say). */
    public static BigDecimal round(BigDecimal amount, int fractionalDigits) {
        return amount.setScale(fractionalDigits < 0 ? 2 : fractionalDigits, RoundingMode.DOWN);
    }

    public static Result transfer(EconomyPort economy, UUID a, BigDecimal aOffer, UUID b, BigDecimal bOffer) {
        int digits = economy.fractionalDigits();
        BigDecimal net = round(aOffer, digits).subtract(round(bOffer, digits));
        if (net.signum() == 0) {
            return new Result(Status.OK, null, null, BigDecimal.ZERO, null);
        }
        UUID payer = net.signum() > 0 ? a : b;
        UUID payee = net.signum() > 0 ? b : a;
        BigDecimal amount = net.abs();

        Response withdrawn = call(() -> economy.withdraw(payer, amount));
        if (!withdrawn.success()) {
            return new Result(Status.WITHDRAW_FAILED, payer, payee, amount, "withdraw: " + withdrawn.error());
        }
        Response deposited = call(() -> economy.deposit(payee, amount));
        if (deposited.success()) {
            return new Result(Status.OK, payer, payee, amount, null);
        }
        Response refunded = call(() -> economy.deposit(payer, amount));
        if (refunded.success()) {
            return new Result(Status.DEPOSIT_FAILED, payer, payee, amount, "deposit: " + deposited.error());
        }
        return new Result(Status.ECONOMY_ERROR, payer, payee, amount,
                "deposit: " + deposited.error() + "; refund: " + refunded.error());
    }

    private interface Call {
        Response run();
    }

    private static Response call(Call call) {
        try {
            Response response = call.run();
            return response == null ? Response.failed("no response") : response;
        } catch (RuntimeException e) {
            return Response.failed("threw " + e);
        }
    }
}
