package com.mrstudios.trades.listener;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.gui.HistoryHolder;
import com.mrstudios.trades.history.HistoryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** History windows are read-only: every click and drag is cancelled; buttons act on plain clicks. */
public final class HistoryListener implements Listener {

    private final MRTrades plugin;
    private final HistoryService history;

    public HistoryListener(MRTrades plugin, HistoryService history) {
        this.plugin = plugin;
        this.history = history;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        HistoryHolder holder = HistoryHolder.of(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            history.click(player, holder, event.getRawSlot());
        }
        // The client predicted the click; send it the real state (needed for Geyser too).
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (HistoryHolder.of(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }
}
