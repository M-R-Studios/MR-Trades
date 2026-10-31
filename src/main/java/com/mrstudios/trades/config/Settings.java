package com.mrstudios.trades.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;

/** Everything in {@code config.yml}, validated. Immutable; a reload builds a new one. */
public record Settings(
        Requests requests,
        Limits limits,
        Trade trade,
        Money money,
        Xp xp,
        Input input,
        Bedrock bedrock,
        Returns returns,
        History history,
        Storage storage,
        Sounds sounds) {

    /** Must match the bundled {@code config.yml}; {@code SettingsLoaderTest} checks that. */
    public static final Settings DEFAULTS = new Settings(
            new Requests(30, 5, true, true),
            new Limits(16, 10, false, Set.of(), Set.of(GameMode.CREATIVE, GameMode.SPECTATOR)),
            new Trade(3, 1000),
            new Money(true, List.of(BigDecimal.valueOf(1), BigDecimal.valueOf(10), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(1000)), BigDecimal.ZERO),
            new Xp(true, List.of(1, 5, 10, 30)),
            new Input(30, "cancel"),
            new Bedrock(true, true, true),
            new Returns(WhenFull.QUEUE),
            new History(true, 90, ""),
            new Storage(StorageType.SQLITE, "history.db",
                    new Mysql("localhost", 3306, "mrtrades", "mrtrades", "", "mrtrades_", 4,
                            Map.of("sslMode", "PREFERRED"))),
            new Sounds(
                    sound("entity.experience_orb.pickup", 1.0f, 1.0f),
                    sound("block.chest.open", 0.8f, 1.2f),
                    sound("ui.button.click", 0.5f, 1.0f),
                    sound("block.note_block.pling", 1.0f, 1.5f),
                    sound("block.note_block.hat", 1.0f, 1.0f),
                    sound("entity.player.levelup", 1.0f, 1.0f),
                    sound("entity.villager.no", 1.0f, 1.0f)));

    private static SoundSetting sound(String key, float volume, float pitch) {
        return new SoundSetting(true, NamespacedKey.minecraft(key), volume, pitch);
    }

    public record Requests(int timeoutSeconds, int cooldownSeconds, boolean sneakRightClick, boolean clickableButtons) {
    }

    /**
     * @param maxDistance blocks, 0 = no limit
     * @param disabledWorlds world names, lower case
     */
    public record Limits(int maxDistance, int distanceCheckTicks, boolean allowCrossWorld,
            Set<String> disabledWorlds, Set<GameMode> blockedGameModes) {

        public Limits {
            disabledWorlds = Set.copyOf(disabledWorlds);
            blockedGameModes = Set.copyOf(blockedGameModes);
        }

        public boolean isWorldDisabled(String worldName) {
            return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    public record Trade(int countdownSeconds, long changeLockMs) {
    }

    /**
     * @param buttons 1-4 positive amounts, smallest first
     * @param maxAmount 0 = no limit
     */
    public record Money(boolean enabled, List<BigDecimal> buttons, BigDecimal maxAmount) {

        public Money {
            buttons = List.copyOf(buttons);
        }
    }

    /** @param buttons 1-4 positive level amounts, smallest first */
    public record Xp(boolean enabled, List<Integer> buttons) {

        public Xp {
            buttons = List.copyOf(buttons);
        }
    }

    /** @param cancelWord compared ignoring case */
    public record Input(int chatTimeoutSeconds, String cancelWord) {
    }

    public record Bedrock(boolean floodgate, boolean requestForm, boolean amountForm) {
    }

    public record Returns(WhenFull whenFull) {
    }

    /** @param serverName as written; may be empty, see {@link #effectiveServerName()} */
    public record History(boolean enabled, int retentionDays, String serverName) {

        /** The name recorded in the log: {@code serverName}, or {@code default} when it's empty. */
        public String effectiveServerName() {
            return serverName.isEmpty() ? "default" : serverName;
        }
    }

    /** @param sqliteFile a file name inside the plugin folder */
    public record Storage(StorageType type, String sqliteFile, Mysql mysql) {
    }

    /** @param properties extra JDBC connection properties */
    public record Mysql(String host, int port, String database, String username, String password,
            String tablePrefix, int poolSize, Map<String, String> properties) {

        public Mysql {
            properties = Map.copyOf(properties);
        }

        @Override
        public String toString() {
            // Keeps the password out of logs and error reports.
            return "Mysql[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username
                    + ", tablePrefix=" + tablePrefix + ", poolSize=" + poolSize + ", properties=" + properties + "]";
        }
    }

    public record Sounds(SoundSetting request, SoundSetting open, SoundSetting change, SoundSetting confirm,
            SoundSetting countdown, SoundSetting complete, SoundSetting cancel) {
    }

    public record SoundSetting(boolean enabled, NamespacedKey sound, float volume, float pitch) {
    }

    /** What happens to returned items that don't fit in the owner's inventory. */
    public enum WhenFull {
        QUEUE,
        DROP
    }

    public enum StorageType {
        SQLITE,
        MYSQL
    }
}
