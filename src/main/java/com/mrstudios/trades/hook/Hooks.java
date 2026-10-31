package com.mrstudios.trades.hook;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.PluginManager;

/**
 * Optional integrations. Floodgate is detected once at startup and changing it needs a restart.
 * Vault is looked up lazily, so it can appear or disappear at any time.
 */
public final class Hooks {

    private static final String VAULT = "Vault";
    private static final String FLOODGATE = "floodgate";

    private final PluginManager plugins;
    private final boolean floodgateAtStartup;

    public Hooks(PluginManager plugins) {
        this.plugins = plugins;
        this.floodgateAtStartup = plugins.isPluginEnabled(FLOODGATE);
    }

    /** Whether Floodgate was enabled when MR-Trades started. */
    public boolean floodgate() {
        return floodgateAtStartup;
    }

    /** For {@code /mrtrades version}: the active hooks, or {@code none}. */
    public String describe() {
        List<String> active = new ArrayList<>();
        if (plugins.isPluginEnabled(VAULT)) {
            active.add("Vault");
        }
        if (floodgateAtStartup) {
            active.add("Floodgate");
        }
        return active.isEmpty() ? "none" : String.join(", ", active);
    }

    /** Hooks that need a restart and whose plugin was added or removed since startup. */
    public List<String> changedSinceStartup() {
        return plugins.isPluginEnabled(FLOODGATE) != floodgateAtStartup ? List.of("Floodgate") : List.of();
    }
}
