package com.mrstudios.trades.listener;

import com.mrstudios.trades.gui.DisplayItems;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * DESIGN.md 3.5 #24: a display copy outside a trade window means a bug or an exploit. It is
 * removed wherever it shows up. The real item is always safe in its trade.
 */
public final class DisplayGuardListener implements Listener {

    private final DisplayItems display;
    private final Logger logger;

    public DisplayGuardListener(DisplayItems display, Logger logger) {
        this.display = display;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(ItemSpawnEvent event) {
        if (display.isDisplay(event.getEntity().getItemStack())) {
            event.setCancelled(true);
            logger.warning("Removed a trade display copy that was dropped at " + event.getLocation().toVector()
                    + ". Display copies should never leave a trade window.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (display.isDisplay(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            logger.warning("Removed a trade display copy that " + event.getEntity().getName() + " tried to pick up.");
        }
    }
}
