package com.mrstudios.trades;

import com.mrstudios.trades.command.AdminCommand;
import com.mrstudios.trades.command.TradeCommand;
import com.mrstudios.trades.config.LoadResult;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.MessagesLoader;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.SettingsLoader;
import com.mrstudios.trades.hook.Hooks;
import com.mrstudios.trades.listener.LifecycleListener;
import com.mrstudios.trades.request.CooldownTracker;
import com.mrstudios.trades.request.RequestManager;
import com.mrstudios.trades.request.RequestService;
import com.mrstudios.trades.request.RequestToggle;
import com.mrstudios.trades.trade.TradeRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

// Not final: MockBukkit subclasses the plugin class in tests.
public class MRTrades extends JavaPlugin {

    /** The active settings and messages. Swapped as a whole on reload. */
    public record State(Settings settings, Messages messages) {
    }

    private volatile State state;
    private SettingsLoader settingsLoader;
    private MessagesLoader messagesLoader;
    private Hooks hooks;
    /** Storage is opened once at startup; changing it needs a restart. */
    private Settings.Storage startupStorage;
    private final TradeRegistry trades = new TradeRegistry();
    private RequestService requests;

    @Override
    public void onEnable() {
        settingsLoader = new SettingsLoader(key -> Registry.SOUNDS.get(key) != null);
        messagesLoader = new MessagesLoader(MessagesLoader.bundledDefaults());

        Files files = loadFiles();
        Settings settings = files.settings.ok() ? files.settings.value() : Settings.DEFAULTS;
        Messages messages = files.messages.ok() ? files.messages.value() : messagesLoader.defaults();
        state = new State(settings, messages);
        startupStorage = settings.storage();
        for (String line : files.warnings()) {
            getLogger().warning(line);
        }
        if (!files.settings.ok()) {
            getLogger().severe("config.yml could not be loaded, using the built-in defaults: " + files.settings.error());
        }
        if (!files.messages.ok()) {
            getLogger().severe("messages.yml could not be loaded, using the built-in defaults: " + files.messages.error());
        }

        hooks = new Hooks(getServer().getPluginManager());
        requests = new RequestService(this, new RequestManager(System::currentTimeMillis),
                new CooldownTracker(System::currentTimeMillis), new RequestToggle(this), trades);
        getServer().getPluginManager().registerEvents(new LifecycleListener(requests), this);
        getServer().getScheduler().runTaskTimer(this, requests::expireTick, 20L, 20L);

        PluginCommand adminCommand = Objects.requireNonNull(getCommand("mrtrades"));
        AdminCommand admin = new AdminCommand(this);
        adminCommand.setExecutor(admin);
        adminCommand.setTabCompleter(admin);
        PluginCommand tradeCommand = Objects.requireNonNull(getCommand("trade"));
        TradeCommand trade = new TradeCommand(this, requests);
        tradeCommand.setExecutor(trade);
        tradeCommand.setTabCompleter(trade);
        updatePermissionMessage();

        getLogger().info("MR-Trades " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
    }

    public State state() {
        return state;
    }

    public Hooks hooks() {
        return hooks;
    }

    public RequestService requests() {
        return requests;
    }

    public TradeRegistry trades() {
        return trades;
    }

    /** The storage settings MR-Trades started with, which stay in use until a restart. */
    public Settings.Storage startupStorage() {
        return startupStorage;
    }

    /**
     * Reloads {@code config.yml} and {@code messages.yml}. A file that fails keeps its previous
     * settings; the sender and the console are told what failed and why.
     */
    public void reload(CommandSender sender) {
        Files files = loadFiles();
        State old = state;
        State updated = new State(
                files.settings.ok() ? files.settings.value() : old.settings(),
                files.messages.ok() ? files.messages.value() : old.messages());
        state = updated;
        updatePermissionMessage();

        Messages messages = updated.messages();
        List<String> warnings = files.warnings();
        if (warnings.isEmpty() && files.settings.ok() && files.messages.ok()) {
            messages.send(sender, "reload-success");
        }
        if (!warnings.isEmpty()) {
            messages.send(sender, "reload-warnings", Placeholder.unparsed("count", String.valueOf(warnings.size())));
            for (String warning : warnings) {
                messages.send(sender, "reload-detail", Placeholder.unparsed("detail", warning));
            }
        }
        for (LoadResult<?> failed : List.of(files.settings, files.messages)) {
            if (!failed.ok()) {
                String file = failed == files.settings ? "config.yml" : "messages.yml";
                messages.send(sender, "reload-failed", Placeholder.unparsed("file", file));
                messages.send(sender, "reload-detail", Placeholder.unparsed("detail", failed.error()));
            }
        }
        // Things only read at startup are reported, not silently ignored (DESIGN.md 5.2 step 7).
        List<String> needRestart = new ArrayList<>();
        if (!updated.settings().storage().equals(startupStorage)) {
            needRestart.add("config.yml: storage changes need a restart; the storage in use was not changed.");
        }
        for (String hook : hooks.changedSinceStartup()) {
            needRestart.add(hook + " was added or removed since startup; restart the server to change the hook.");
        }
        for (String detail : needRestart) {
            messages.send(sender, "reload-detail", Placeholder.unparsed("detail", detail));
            getLogger().warning(detail);
        }

        if (!(sender instanceof ConsoleCommandSender)) {
            getLogger().info(sender.getName() + " reloaded MR-Trades.");
            for (String line : files.report()) {
                getLogger().warning(line);
            }
        }
    }

    private Files loadFiles() {
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveResource("config.yml", false);
        }
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        LoadResult<Settings> settings = settingsLoader.load(new File(getDataFolder(), "config.yml"), getLogger()::info);
        LoadResult<Messages> messages = messagesLoader.load(new File(getDataFolder(), "messages.yml"), getLogger()::info);
        if (settings.ok()) {
            logUnknownWorlds(settings.value());
        }
        return new Files(settings, messages);
    }

    /** Unknown world names are allowed (the world may load later), but worth a note. */
    private void logUnknownWorlds(Settings settings) {
        Set<String> loaded = Bukkit.getWorlds().stream()
                .map(World::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String world : settings.limits().disabledWorlds()) {
            if (!loaded.contains(world)) {
                getLogger().info("config.yml: limits.disabled-worlds lists '" + world + "', which isn't loaded right now.");
            }
        }
    }

    private void updatePermissionMessage() {
        for (String name : List.of("mrtrades")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.permissionMessage(state.messages().component("no-permission"));
            }
        }
    }

    private record Files(LoadResult<Settings> settings, LoadResult<Messages> messages) {

        List<String> warnings() {
            List<String> warnings = new ArrayList<>();
            settings.warnings().forEach(w -> warnings.add("config.yml: " + w));
            messages.warnings().forEach(w -> warnings.add("messages.yml: " + w));
            return warnings;
        }

        /** Console lines: every warning, then every failure. */
        List<String> report() {
            List<String> lines = new ArrayList<>(warnings());
            if (!settings.ok()) {
                lines.add("Kept the previous config.yml settings: " + settings.error());
            }
            if (!messages.ok()) {
                lines.add("Kept the previous messages.yml: " + messages.error());
            }
            return lines;
        }
    }
}
