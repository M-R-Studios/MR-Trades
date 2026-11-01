package com.mrstudios.trades.hook;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;

/**
 * Optional integrations. Floodgate is detected once at startup and changing it needs a restart.
 * Vault is looked up lazily, so it can appear or disappear at any time.
 */
public final class Hooks {

    private static final String VAULT = "Vault";
    private static final String FLOODGATE = "floodgate";

    private final Server server;
    private final PluginManager plugins;
    private final boolean floodgateAtStartup;
    private EconomyPort vault;
    private EconomyPort economyOverride;
    private BedrockForms floodgate;
    private BedrockForms formsOverride;
    private boolean floodgateTried;

    public Hooks(Server server) {
        this.server = server;
        this.plugins = server.getPluginManager();
        this.floodgateAtStartup = plugins.isPluginEnabled(FLOODGATE);
    }

    /** Whether Floodgate was enabled when MR-Trades started. */
    public boolean floodgate() {
        return floodgateAtStartup;
    }

    /**
     * The economy, or null when Vault isn't enabled. The returned port may still have no economy
     * behind it ({@link EconomyPort#isAvailable()}).
     */
    public EconomyPort economy() {
        if (economyOverride != null) {
            return economyOverride;
        }
        if (!plugins.isPluginEnabled(VAULT)) {
            return null;
        }
        if (vault == null) {
            // Only reached with Vault present, so Vault's classes exist.
            vault = VaultHook.create(server);
        }
        return vault;
    }

    /**
     * Floodgate's Bedrock detection and forms, or null when Floodgate wasn't enabled at startup.
     * Whether to use them is the {@code bedrock.*} settings' call.
     */
    public BedrockForms bedrock() {
        if (formsOverride != null) {
            return formsOverride;
        }
        if (!floodgateAtStartup) {
            return null;
        }
        if (!floodgateTried) {
            floodgateTried = true;
            // Only reached with Floodgate enabled, so its classes exist.
            floodgate = FloodgateHook.create(server.getLogger());
        }
        return floodgate;
    }

    /** For tests: use {@code forms} instead of Floodgate. */
    public void overrideBedrock(BedrockForms forms) {
        this.formsOverride = forms;
    }

    /** For tests: use {@code economy} instead of Vault. */
    public void overrideEconomy(EconomyPort economy) {
        this.economyOverride = economy;
    }

    /** For {@code /mrtrades version}: the active hooks, or {@code none}. */
    public String describe() {
        List<String> active = new ArrayList<>();
        EconomyPort economy = economy();
        if (economy != null) {
            active.add(economy.isAvailable() ? "Vault (" + economy.name() + ")" : "Vault (no economy)");
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
