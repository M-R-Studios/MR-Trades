package com.mrstudios.trades.listener;

import com.mrstudios.trades.trade.TradeService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;

/**
 * DESIGN.md 3.5 #17: when the economy goes away mid-trade, money offers are set to 0. Checked a
 * tick later, once the plugin is fully gone. Names Vault's class only as a string, so this
 * listener loads without Vault.
 */
public final class EconomyListener implements Listener {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final Plugin plugin;
    private final TradeService trades;

    public EconomyListener(Plugin plugin, TradeService trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != plugin) {
            later();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        if (ECONOMY_CLASS.equals(event.getProvider().getService().getName())) {
            later();
        }
    }

    private void later() {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, trades::economyCheck);
        }
    }
}
