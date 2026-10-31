package com.mrstudios.trades.listener;

import com.mrstudios.trades.request.RequestService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Players leaving. For now: drops their trade requests both ways (DESIGN.md 3.5 #9). */
public final class LifecycleListener implements Listener {

    private final RequestService requests;

    public LifecycleListener(RequestService requests) {
        this.requests = requests;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        requests.quit(event.getPlayer());
    }
}
