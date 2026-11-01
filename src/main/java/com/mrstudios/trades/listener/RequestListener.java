package com.mrstudios.trades.listener;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.request.RequestService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Sneak and right-click another player to request a trade, or to accept their pending request
 * (DESIGN.md 1, 3.8). Off with {@code requests.sneak-right-click: false}; also needs
 * {@code mrtrades.trade.sneak} (and {@code mrtrades.trade}). Never fires for NPCs.
 *
 * <p>Runs after protection plugins ({@code ignoreCancelled}), so a region that denies interacting
 * with players denies this too. While trading, interactions are already cancelled.
 */
public final class RequestListener implements Listener {

    public static final String PERMISSION_SNEAK = "mrtrades.trade.sneak";
    /** Holding right-click repeats the interaction; one request per target per this many ms. */
    static final long REPEAT_MS = 1000;

    private final MRTrades plugin;
    private final RequestService requests;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public RequestListener(MRTrades plugin, RequestService requests, LongSupplier clock) {
        this.plugin = plugin;
        this.requests = requests;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneakClick(PlayerInteractEntityEvent event) {
        // Fires once per hand; only the main hand counts, or every click would act twice.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !plugin.state().settings().requests().sneakRightClick()) {
            return;
        }
        Player target = realPlayer(event.getRightClicked());
        if (target == null || target.equals(player)) {
            return;
        }
        if (!player.hasPermission(RequestService.PERMISSION_TRADE) || !player.hasPermission(PERMISSION_SNEAK)) {
            return;
        }
        long now = clock.getAsLong();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < REPEAT_MS) {
            return;
        }
        lastClick.put(player.getUniqueId(), now);
        requests.send(player, target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    /**
     * {@code entity} as a real online player, or null. NPC plugins (Citizens and others) spawn
     * entities that are {@code Player} instances but aren't the online player with that UUID, and
     * Citizens marks them with {@code NPC} metadata (DESIGN.md 3.8).
     */
    static Player realPlayer(Entity entity) {
        if (!(entity instanceof Player player) || player.hasMetadata("NPC")) {
            return null;
        }
        Player online = Bukkit.getPlayer(player.getUniqueId());
        return online == player ? online : null;
    }
}
