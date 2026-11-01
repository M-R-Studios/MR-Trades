package com.mrstudios.trades.trade;

import com.mrstudios.trades.hook.EconomyPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An in-memory economy that can be told to fail or throw on a given call. */
final class FakeEconomy implements EconomyPort {

    enum Mode { OK, FAIL, THROW }

    final Map<UUID, BigDecimal> balances = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    boolean available = true;
    int digits = 2;
    /** The mode for the Nth withdraw/deposit call (1-based, counting both); others are OK. */
    final Map<Integer, Mode> plan = new HashMap<>();
    private int callCount;

    BigDecimal balanceOf(UUID player) {
        return balances.getOrDefault(player, BigDecimal.ZERO);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return balanceOf(player).compareTo(amount) >= 0;
    }

    @Override
    public BigDecimal balance(UUID player) {
        return balanceOf(player);
    }

    private Response apply(String op, UUID player, BigDecimal delta) {
        calls.add(op + " " + delta.abs().toPlainString());
        Mode mode = plan.getOrDefault(++callCount, Mode.OK);
        if (mode == Mode.THROW) {
            throw new IllegalStateException("economy blew up on " + op);
        }
        if (mode == Mode.FAIL) {
            return Response.failed(op + " refused");
        }
        BigDecimal next = balanceOf(player).add(delta);
        if (next.signum() < 0) {
            return Response.failed("insufficient funds");
        }
        balances.put(player, next);
        return Response.ok();
    }

    @Override
    public Response withdraw(UUID player, BigDecimal amount) {
        return apply("withdraw", player, amount.negate());
    }

    @Override
    public Response deposit(UUID player, BigDecimal amount) {
        return apply("deposit", player, amount);
    }

    @Override
    public int fractionalDigits() {
        return digits;
    }

    @Override
    public String format(BigDecimal amount) {
        return "$" + amount.toPlainString();
    }

    @Override
    public String name() {
        return "FakeEconomy";
    }
}
