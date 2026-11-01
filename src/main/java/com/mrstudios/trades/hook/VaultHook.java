package com.mrstudios.trades.hook;

import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Money through Vault. The economy provider is looked up on every call, so an economy plugin that
 * loads, reloads or unloads after MR-Trades is picked up without a restart (DESIGN.md 5.2). This
 * is the only class that touches Vault; it's only loaded when Vault is installed.
 */
final class VaultHook implements EconomyPort {

    private final Server server;

    private VaultHook(Server server) {
        this.server = server;
    }

    /** Only call when Vault's classes are present. */
    static EconomyPort create(Server server) {
        return new VaultHook(server);
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    private Economy require() {
        Economy economy = economy();
        if (economy == null) {
            throw new IllegalStateException("No economy is registered with Vault");
        }
        return economy;
    }

    private OfflinePlayer player(UUID id) {
        return server.getOfflinePlayer(id);
    }

    @Override
    public boolean isAvailable() {
        Economy economy = economy();
        return economy != null && economy.isEnabled();
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return require().has(player(player), amount.doubleValue());
    }

    @Override
    public BigDecimal balance(UUID player) {
        double balance = require().getBalance(player(player));
        return Double.isFinite(balance) ? BigDecimal.valueOf(balance) : BigDecimal.ZERO;
    }

    @Override
    public Response withdraw(UUID player, BigDecimal amount) {
        return response(require().withdrawPlayer(player(player), amount.doubleValue()));
    }

    @Override
    public Response deposit(UUID player, BigDecimal amount) {
        return response(require().depositPlayer(player(player), amount.doubleValue()));
    }

    private static Response response(EconomyResponse response) {
        if (response == null) {
            return Response.failed("the economy returned no response");
        }
        return response.transactionSuccess() ? Response.ok() : Response.failed(response.errorMessage);
    }

    @Override
    public int fractionalDigits() {
        Economy economy = economy();
        return economy == null ? -1 : economy.fractionalDigits();
    }

    @Override
    public String format(BigDecimal amount) {
        Economy economy = economy();
        return economy == null ? amount.toPlainString() : economy.format(amount.doubleValue());
    }

    @Override
    public String name() {
        Economy economy = economy();
        return economy == null ? "none" : economy.getName();
    }
}
