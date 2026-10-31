package com.mrstudios.trades.command;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** {@code /mrtrades [help|reload|version]}. */
public final class AdminCommand implements TabExecutor {

    private static final String PERMISSION = "mrtrades.admin";
    private static final List<String> SUBCOMMANDS = List.of("reload", "version");

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
            default -> messages.sendLines(sender, "admin-help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(typed)).toList();
    }
}
