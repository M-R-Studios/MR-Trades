package com.mrstudios.trades.command;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.request.RequestService;
import com.mrstudios.trades.returns.ReturnService;
import com.mrstudios.trades.returns.ReturnService.Delivery;
import com.mrstudios.trades.trade.EndReason;
import com.mrstudios.trades.trade.TradeService;
import com.mrstudios.trades.trade.TradeSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /trade [player|accept|deny|cancel|toggle|claim]}. No permission in {@code plugin.yml},
 * because {@code /trade claim} must work without one (DESIGN.md 2.3); each subcommand checks its
 * own. {@code history} arrives with step 9; until then it shows the help.
 */
public final class TradeCommand implements TabExecutor {

    private static final String PERMISSION_TOGGLE = "mrtrades.toggle";
    /** Subcommand names. A player with one of these names can't be requested by name. */
    private static final List<String> RESERVED = List.of("accept", "deny", "cancel", "toggle", "claim", "history");

    private final MRTrades plugin;
    private final RequestService requests;
    private final TradeService trades;
    private final ReturnService returns;

    public TradeCommand(MRTrades plugin, RequestService requests, TradeService trades, ReturnService returns) {
        this.plugin = plugin;
        this.requests = requests;
        this.trades = trades;
        this.returns = returns;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.state().messages();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String name = args.length > 1 ? args[1] : null;
        if (sub.equals("claim")) {
            // Never gated by a permission: it's how players get their own items back.
            claim(player, messages);
            return true;
        }
        if (sub.equals("toggle")) {
            if (!player.hasPermission(PERMISSION_TOGGLE)) {
                messages.send(player, "no-permission");
                return true;
            }
            requests.toggle(player);
            return true;
        }
        if (!player.hasPermission(RequestService.PERMISSION_TRADE)) {
            messages.send(player, "no-permission");
            return true;
        }
        switch (sub) {
            case "accept" -> requests.accept(player, name);
            case "deny" -> requests.deny(player, name);
            case "cancel" -> cancel(player);
            case "", "history" -> messages.sendLines(player, "help");
            default -> requests.send(player, args[0]);
        }
        return true;
    }

    /** In a trade: cancels it, like the cancel button. Otherwise withdraws your requests. */
    private void cancel(Player player) {
        TradeSession session = trades.registry().get(player.getUniqueId());
        if (session != null) {
            trades.abort(session, EndReason.CANCELLED, player.getUniqueId(), Set.of());
        } else {
            requests.cancel(player);
        }
    }

    private void claim(Player player, Messages messages) {
        Delivery delivery;
        try {
            delivery = returns.deliverQueue(player);
        } catch (IllegalStateException e) {
            messages.send(player, "returns-none");
            return;
        }
        if (delivery.given() == 0 && delivery.left() == 0) {
            messages.send(player, "returns-none");
        } else {
            messages.send(player, "returns-claimed", Placeholder.unparsed("count", String.valueOf(delivery.given())),
                    Placeholder.unparsed("left", String.valueOf(delivery.left())));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        boolean canTrade = player.hasPermission(RequestService.PERMISSION_TRADE);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("claim");
            if (canTrade) {
                options.addAll(List.of("accept", "deny", "cancel"));
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (!online.equals(player) && player.canSee(online)
                            && !RESERVED.contains(online.getName().toLowerCase(Locale.ROOT))) {
                        options.add(online.getName());
                    }
                }
            }
            if (player.hasPermission(PERMISSION_TOGGLE)) {
                options.add("toggle");
            }
        } else if (args.length == 2 && canTrade
                && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            options.addAll(requests.requesterNames(player));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }
}
