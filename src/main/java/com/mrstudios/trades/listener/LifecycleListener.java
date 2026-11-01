package com.mrstudios.trades.listener;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.TradeHolder;
import com.mrstudios.trades.request.RequestService;
import com.mrstudios.trades.returns.ReturnService;
import com.mrstudios.trades.returns.ReturnService.Delivery;
import com.mrstudios.trades.trade.EndReason;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeService;
import com.mrstudios.trades.trade.TradeSession;
import com.mrstudios.trades.trade.ViewState;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Everything that ends a trade from outside the window, and getting items back to their owners
 * (DESIGN.md 3.5 #8-#15, #19, #20). Kicks need no handler: a kick is always followed by a quit.
 */
public final class LifecycleListener implements Listener {

    private final MRTrades plugin;
    private final TradeService trades;
    private final RequestService requests;
    private final ReturnService returns;

    public LifecycleListener(MRTrades plugin, TradeService trades, RequestService requests, ReturnService returns) {
        this.plugin = plugin;
        this.trades = trades;
        this.requests = requests;
        this.returns = returns;
    }

    private TradeSession sessionOf(Player player) {
        return trades.registry().get(player.getUniqueId());
    }

    /** #8: closing the window ends the trade, unless the plugin closed it on purpose. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        TradeHolder holder = TradeHolder.of(event.getInventory());
        if (holder == null) {
            editorClosed(event, player);
            return;
        }
        TradeSession session = holder.session();
        Side side = holder.side();
        if (session.isEnded() || !side.playerId().equals(player.getUniqueId())) {
            return;
        }
        ViewState view = side.view();
        if (view == ViewState.INPUT_CHAT || view == ViewState.INPUT_FORM || view == ViewState.REOPENING) {
            return;
        }
        trades.abort(session, EndReason.CLOSED, player.getUniqueId(), Set.of());
    }

    /**
     * #8 for the editor: Esc discards the draft and reopens the trade window. Any other close
     * (another plugin, teleport) ends the trade like closing the trade window would.
     */
    private void editorClosed(InventoryCloseEvent event, Player player) {
        if (!trades.registry().isTrading(player.getUniqueId())) {
            return;
        }
        EditorHolder editor = EditorHolder.of(event.getInventory());
        if (editor == null || editor.session().isEnded() || !editor.side().playerId().equals(player.getUniqueId())
                || editor.side().view() != ViewState.EDITOR) {
            return;
        }
        if (event.getReason() == InventoryCloseEvent.Reason.PLAYER) {
            trades.editorClosed(player, editor);
        } else {
            trades.abort(editor.session(), EndReason.CLOSED, player.getUniqueId(), Set.of());
        }
    }

    /** #9: the player is still online here, so their items go back into their inventory and are saved. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        TradeSession session = sessionOf(player);
        if (session != null) {
            trades.abort(session, EndReason.QUIT, player.getUniqueId(), Set.of());
        }
        requests.quit(player);
    }

    /** #11: the dying player's items go to their return queue, never into the death drops. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        TradeSession session = sessionOf(player);
        if (session != null) {
            trades.abort(session, EndReason.DIED, player.getUniqueId(), Set.of(player.getUniqueId()));
        }
    }

    /** #12: a teleport only matters if it breaks a limit; a short pearl throw doesn't. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        TradeSession session = sessionOf(event.getPlayer());
        if (session != null) {
            Bukkit.getScheduler().runTask(plugin, () -> trades.checkLimits(session));
        }
    }

    /** #13. Checked a tick later, once the player is fully in the new world. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        TradeSession session = sessionOf(event.getPlayer());
        if (session != null) {
            Bukkit.getScheduler().runTask(plugin, () -> trades.checkLimits(session));
        }
    }

    /** #14. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        TradeSession session = sessionOf(player);
        if (session != null && plugin.state().settings().limits().blockedGameModes().contains(event.getNewGameMode())) {
            trades.abort(session, EndReason.GAMEMODE, player.getUniqueId(), Set.of());
        }
    }

    /** #19: recover offers left by a crash, then give back whatever is waiting. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        trades.claimOrphans(player);
        Delivery delivery;
        try {
            delivery = returns.recoverAndDeliver(player, id -> {
                TradeSession live = sessionOf(player);
                return live != null && live.id().equals(id);
            });
        } catch (IllegalStateException e) {
            return; // Logged by the store; the record is left in place.
        }
        if (delivery.left() > 0) {
            plugin.state().messages().send(player, "returns-waiting",
                    Placeholder.unparsed("count", String.valueOf(delivery.left())));
        }
    }

    /** #20. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        try {
            Delivery delivery = returns.deliverQueue(player);
            if (delivery.given() > 0) {
                plugin.state().messages().send(player, "returns-claimed",
                        Placeholder.unparsed("count", String.valueOf(delivery.given())),
                        Placeholder.unparsed("left", String.valueOf(delivery.left())));
            }
        } catch (IllegalStateException e) {
            // Logged by the store; the record is left in place.
        }
    }
}
