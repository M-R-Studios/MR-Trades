package com.mrstudios.trades.history;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.gui.HistoryHolder;
import com.mrstudios.trades.gui.HistoryView;
import com.mrstudios.trades.trade.TradeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

/**
 * {@code /trade history [player] [page]} and the clicks in its windows (DESIGN.md 6.5). Queries run
 * on the log's thread; the window opens on the main thread, only if the player is still online
 * and not trading. Every button is a plain slot click, so it all works on Bedrock.
 */
public final class HistoryService {

    public static final String PERMISSION = "mrtrades.history";
    public static final String PERMISSION_OTHERS = "mrtrades.history.others";

    private final MRTrades plugin;
    private final Supplier<TradeLog> log;
    private final HistoryView view;
    private final Predicate<Player> bedrock;
    private final Predicate<UUID> trading;

    public HistoryService(MRTrades plugin, Supplier<TradeLog> log, HistoryView view, Predicate<Player> bedrock,
            Predicate<UUID> trading) {
        this.plugin = plugin;
        this.log = log;
        this.view = view;
        this.bedrock = bedrock;
        this.trading = trading;
    }

    private Messages messages() {
        return plugin.state().messages();
    }

    /** @param args what follows {@code history}: {@code [player] [page]}, or just {@code [page]} */
    public void command(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            messages().send(player, "no-permission");
            return;
        }
        TradeLog tradeLog = log.get();
        if (tradeLog == null || !plugin.state().settings().history().enabled()) {
            messages().send(player, "history-disabled");
            return;
        }
        if (tradeLog.status() == TradeLog.Status.FAILED) {
            messages().send(player, "history-loading-failed");
            return;
        }
        if (trading.test(player.getUniqueId())) {
            messages().send(player, "self-busy");
            return;
        }
        String name = null;
        int page = 1;
        if (args.length == 1 && number(args[0]) > 0) {
            page = number(args[0]);
        } else if (args.length >= 1) {
            name = args[0];
            if (args.length >= 2 && number(args[1]) > 0) {
                page = number(args[1]);
            }
        }
        if (name == null || name.equalsIgnoreCase(player.getName())) {
            show(player, new HistoryQuery(player.getUniqueId(), player.getName(), page));
            return;
        }
        if (!player.hasPermission(PERMISSION_OTHERS)) {
            messages().send(player, "no-permission");
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            show(player, new HistoryQuery(cached.getUniqueId(), cached.getName() == null ? name : cached.getName(), page));
            return;
        }
        // Not known to the server (e.g. user cache cleared): the log knows names too.
        String typed = name;
        int wanted = page;
        whenDone(player, tradeLog.findName(name), (Optional<Database.NameMatch> match) -> {
            if (match.isEmpty()) {
                messages().send(player, "history-unknown-player", Placeholder.unparsed("player", typed));
            } else {
                show(player, new HistoryQuery(match.get().id(), match.get().name(), wanted));
            }
        });
    }

    /** Positive whole number, or -1. */
    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Loads a page and opens it. */
    void show(Player player, HistoryQuery query) {
        TradeLog tradeLog = log.get();
        if (tradeLog == null) {
            messages().send(player, "history-disabled");
            return;
        }
        whenDone(player, tradeLog.page(query), result -> {
            if (result.total() == 0) {
                if (query.player().equals(player.getUniqueId())) {
                    messages().send(player, "history-empty");
                } else {
                    messages().send(player, "history-unknown-player", Placeholder.unparsed("player", query.name()));
                }
                return;
            }
            open(player, view.list(result));
        });
    }

    /** Runs {@code then} on the main thread if the player is still online and not trading. */
    private <T> void whenDone(Player player, CompletableFuture<T> future, Consumer<T> then) {
        future.whenComplete((value, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || trading.test(player.getUniqueId())) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "Couldn't load the trade history for " + player.getName(), error);
                    messages().send(player, "history-loading-failed");
                    return;
                }
                then.accept(value);
            });
        });
    }

    /**
     * Opens {@code inventory}. Called from scheduled tasks, never inside an inventory event. On
     * Bedrock, replacing one window with another closes, pauses and then opens, like the trade
     * window does, so the client's late close can't shut the new window.
     */
    private void open(Player player, Inventory inventory) {
        if (bedrock.test(player) && HistoryHolder.of(player.getOpenInventory().getTopInventory()) != null) {
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && !trading.test(player.getUniqueId())) {
                    player.openInventory(inventory);
                }
            }, TradeService.BEDROCK_SWITCH_TICKS);
            return;
        }
        player.openInventory(inventory);
    }

    /** A click in a history window (already cancelled). */
    public void click(Player player, HistoryHolder holder, int slot) {
        HistoryQuery.Result page = holder.page();
        if (holder.detail() != null) {
            if (slot == GuiLayout.DETAIL_BACK) {
                later(() -> open(player, view.list(page)));
            }
            return;
        }
        List<TradeRecord> rows = page.rows();
        if (slot >= 0 && slot < rows.size() && slot < HistoryQuery.PAGE_SIZE) {
            TradeRecord record = rows.get(slot);
            later(() -> open(player, view.detail(page, record)));
        } else if (slot == GuiLayout.HISTORY_PREVIOUS && page.hasPrevious()) {
            show(player, withPage(page.query(), page.query().page() - 1));
        } else if (slot == GuiLayout.HISTORY_NEXT && page.hasNext()) {
            show(player, withPage(page.query(), page.query().page() + 1));
        } else if (slot == GuiLayout.HISTORY_CLOSE) {
            later(() -> player.closeInventory(InventoryCloseEvent.Reason.PLUGIN));
        }
    }

    private static HistoryQuery withPage(HistoryQuery query, int page) {
        return new HistoryQuery(query.player(), query.name(), page);
    }

    /** Next tick: windows aren't opened or closed inside the click event. */
    private void later(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
