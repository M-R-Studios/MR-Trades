package com.mrstudios.trades.command;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /mrtrades [help|reload|version|cancel <player>]}. */
public final class AdminCommand implements TabExecutor {

    private static final String PERMISSION = "mrtrades.admin";
    private static final List<String> SUBCOMMANDS = List.of("reload", "version", "cancel");

    private final MRTrades plugin;

    public AdminCommand(MRTrades plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.state().messages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> plugin.reload(sender);
            case "version" -> messages.send(sender, "version",
                    Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()),
                    Placeholder.unparsed("server", Bukkit.getName() + " " + Bukkit.getMinecraftVersion()),
                    Placeholder.unparsed("hooks", plugin.hooks().describe()),
                    Placeholder.unparsed("storage", plugin.startupStorage().type().name()));
            case "cancel" -> {
                if (args.length < 2) {
                    messages.sendLines(sender, "admin-help");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                String name = target == null ? args[1] : target.getName();
                boolean cancelled = target != null && plugin.trades().adminCancel(target.getUniqueId());
                messages.send(sender, cancelled ? "admin-cancelled" : "admin-not-trading",
                        Placeholder.unparsed("player", name));
            }
            default -> messages.sendLines(sender, "admin-help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return plugin.trades().registry().sessions().stream()
                    .flatMap(s -> Stream.of(s.a().name(), s.b().name()))
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(typed)).toList();
    }
}
