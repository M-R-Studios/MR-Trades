package com.mrstudios.trades.config;

import com.mrstudios.trades.config.Settings.Bedrock;
import com.mrstudios.trades.config.Settings.History;
import com.mrstudios.trades.config.Settings.Input;
import com.mrstudios.trades.config.Settings.Limits;
import com.mrstudios.trades.config.Settings.Money;
import com.mrstudios.trades.config.Settings.Mysql;
import com.mrstudios.trades.config.Settings.Requests;
import com.mrstudios.trades.config.Settings.Returns;
import com.mrstudios.trades.config.Settings.SoundSetting;
import com.mrstudios.trades.config.Settings.Sounds;
import com.mrstudios.trades.config.Settings.Storage;
import com.mrstudios.trades.config.Settings.StorageType;
import com.mrstudios.trades.config.Settings.Trade;
import com.mrstudios.trades.config.Settings.WhenFull;
import com.mrstudios.trades.config.Settings.Xp;
import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link Settings} from {@code config.yml}. Every invalid or missing value falls back to its
 * default with a warning that names the key, the bad value and what is accepted.
 */
public final class SettingsLoader {

    private static final List<String> SOUND_NAMES =
            List.of("request", "open", "change", "confirm", "countdown", "complete", "cancel");

    private static final List<String> KNOWN_KEYS = knownKeys();

    /** Sections whose keys are free-form (any key is fine). */
    private static final List<String> FREE_SECTIONS = List.of("storage.mysql.properties");

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]*");
    private static final int MAX_BUTTONS = 4;
    private static final int MAX_SERVER_NAME = 64;

    private final Predicate<NamespacedKey> soundExists;
    private final ConfigMigrator migrator;

    /** @param soundExists whether a key names a sound in the server's sound registry */
    public SettingsLoader(Predicate<NamespacedKey> soundExists) {
        this(soundExists, ConfigMigrator.forConfig());
    }

    SettingsLoader(Predicate<NamespacedKey> soundExists, ConfigMigrator migrator) {
        this.soundExists = soundExists;
        this.migrator = migrator;
    }

    private static List<String> knownKeys() {
        List<String> keys = new ArrayList<>(List.of(
                "config-version",
                "requests.timeout-seconds",
                "requests.cooldown-seconds",
                "requests.sneak-right-click",
                "requests.clickable-buttons",
                "limits.max-distance",
                "limits.distance-check-ticks",
                "limits.allow-cross-world",
                "limits.disabled-worlds",
                "limits.blocked-gamemodes",
                "trade.countdown-seconds",
                "trade.change-lock-ms",
                "money.enabled",
                "money.buttons",
                "money.max-amount",
                "xp.enabled",
                "xp.buttons",
                "input.chat-timeout-seconds",
                "input.cancel-word",
                "bedrock.floodgate",
                "bedrock.request-form",
                "bedrock.amount-form",
                "returns.when-full",
                "history.enabled",
                "history.retention-days",
                "history.server-name",
                "storage.type",
                "storage.sqlite.file",
                "storage.mysql.host",
                "storage.mysql.port",
                "storage.mysql.database",
                "storage.mysql.username",
                "storage.mysql.password",
                "storage.mysql.table-prefix",
                "storage.mysql.pool-size",
                "storage.mysql.properties"));
        for (String sound : SOUND_NAMES) {
            for (String field : List.of("enabled", "sound", "volume", "pitch")) {
                keys.add("sounds." + sound + "." + field);
            }
        }
        return List.copyOf(keys);
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<Settings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<Settings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<Settings> read(YamlConfiguration yaml) {
        Reader r = new Reader(yaml);
        Settings d = Settings.DEFAULTS;

        Requests requests = new Requests(
                r.wholeNumber("requests.timeout-seconds", 5, Integer.MAX_VALUE, d.requests().timeoutSeconds()),
                r.wholeNumber("requests.cooldown-seconds", 0, Integer.MAX_VALUE, d.requests().cooldownSeconds()),
                r.bool("requests.sneak-right-click", d.requests().sneakRightClick()),
                r.bool("requests.clickable-buttons", d.requests().clickableButtons()));
        Limits limits = new Limits(
                r.wholeNumber("limits.max-distance", 0, Integer.MAX_VALUE, d.limits().maxDistance()),
                r.wholeNumber("limits.distance-check-ticks", 5, 100, d.limits().distanceCheckTicks()),
                r.bool("limits.allow-cross-world", d.limits().allowCrossWorld()),
                r.worlds("limits.disabled-worlds", d.limits().disabledWorlds()),
                r.gameModes("limits.blocked-gamemodes", d.limits().blockedGameModes()));
        Trade trade = new Trade(
                r.wholeNumber("trade.countdown-seconds", 0, 10, d.trade().countdownSeconds()),
                r.wholeNumber("trade.change-lock-ms", 0, Integer.MAX_VALUE, (int) d.trade().changeLockMs()));
        Money money = new Money(
                r.bool("money.enabled", d.money().enabled()),
                r.moneyButtons("money.buttons", d.money().buttons()),
                r.amount("money.max-amount", d.money().maxAmount()));
        Xp xp = new Xp(
                r.bool("xp.enabled", d.xp().enabled()),
                r.xpButtons("xp.buttons", d.xp().buttons()));
        Input input = new Input(
                r.wholeNumber("input.chat-timeout-seconds", 5, Integer.MAX_VALUE, d.input().chatTimeoutSeconds()),
                r.text("input.cancel-word", d.input().cancelWord(),
                        s -> !s.isEmpty() && s.chars().noneMatch(Character::isWhitespace), "a single word"));
        Bedrock bedrock = new Bedrock(
                r.bool("bedrock.floodgate", d.bedrock().floodgate()),
                r.bool("bedrock.request-form", d.bedrock().requestForm()),
                r.bool("bedrock.amount-form", d.bedrock().amountForm()));
        Returns returns = new Returns(r.choice("returns.when-full", WhenFull.class, d.returns().whenFull()));
        History history = new History(
                r.bool("history.enabled", d.history().enabled()),
                r.wholeNumber("history.retention-days", 0, Integer.MAX_VALUE, d.history().retentionDays()),
                r.text("history.server-name", d.history().serverName(),
                        s -> s.length() <= MAX_SERVER_NAME, "text of at most " + MAX_SERVER_NAME + " characters"));
        Mysql dm = d.storage().mysql();
        Storage storage = new Storage(
                r.choice("storage.type", StorageType.class, d.storage().type()),
                r.text("storage.sqlite.file", d.storage().sqliteFile(),
                        s -> !s.isEmpty() && !s.contains("/") && !s.contains("\\") && !s.equals("..")
                                && !s.equals("."),
                        "a file name without folders"),
                new Mysql(
                        r.text("storage.mysql.host", dm.host(), s -> !s.isEmpty(), "a host name"),
                        r.wholeNumber("storage.mysql.port", 1, 65535, dm.port()),
                        r.text("storage.mysql.database", dm.database(), s -> !s.isEmpty(), "a database name"),
                        r.text("storage.mysql.username", dm.username(), s -> true, "text"),
                        r.text("storage.mysql.password", dm.password(), s -> true, "text"),
                        r.text("storage.mysql.table-prefix", dm.tablePrefix(), s -> TABLE_PREFIX.matcher(s).matches(),
                                "letters, digits and _ only"),
                        r.wholeNumber("storage.mysql.pool-size", 1, 20, dm.poolSize()),
                        r.properties("storage.mysql.properties", dm.properties())));
        Sounds ds = d.sounds();
        Sounds sounds = new Sounds(
                r.sound("sounds.request", ds.request()),
                r.sound("sounds.open", ds.open()),
                r.sound("sounds.change", ds.change()),
                r.sound("sounds.confirm", ds.confirm()),
                r.sound("sounds.countdown", ds.countdown()),
                r.sound("sounds.complete", ds.complete()),
                r.sound("sounds.cancel", ds.cancel()));

        Settings settings = new Settings(requests, limits, trade, money, xp, input, bedrock, returns, history,
                storage, sounds);
        r.warnUnknownKeys();
        return LoadResult.loaded(settings, r.warnings);
    }

    private final class Reader {

        private final YamlConfiguration yaml;
        private final List<String> warnings = new ArrayList<>();

        Reader(YamlConfiguration yaml) {
            this.yaml = yaml;
        }

        private Object value(String path, Object def) {
            Object value = yaml.get(path);
            if (value == null) {
                warnings.add(path + " is missing, using " + def);
            }
            return value;
        }

        private void invalid(String path, Object value, String accepted, Object def) {
            warnings.add(path + ": '" + value + "' is not " + accepted + ", using " + def);
        }

        private static boolean isScalar(Object value) {
            return value instanceof String || value instanceof Number || value instanceof Boolean;
        }

        boolean bool(String path, boolean def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            invalid(path, value, "true or false", def);
            return def;
        }

        int wholeNumber(String path, int min, int max, int def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            if (value instanceof Integer || value instanceof Long) {
                long n = ((Number) value).longValue();
                if (n >= min && n <= max) {
                    return (int) n;
                }
            }
            String accepted = max == Integer.MAX_VALUE
                    ? "a whole number of at least " + min
                    : "a whole number from " + min + " to " + max;
            invalid(path, value, accepted, def);
            return def;
        }

        float decimal(String path, double min, double max, float def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            if (value instanceof Number n) {
                double d = n.doubleValue();
                if (d >= min && d <= max) {
                    return (float) d;
                }
            }
            invalid(path, value, "a number from " + min + " to " + max, def);
            return def;
        }

        /** Text, trimmed. Numbers and booleans are accepted as text (a password may be all digits). */
        String text(String path, String def, Predicate<String> valid, String accepted) {
            Object value = value(path, "\"" + def + "\"");
            if (value == null) {
                return def;
            }
            if (isScalar(value)) {
                String s = value.toString().strip();
                if (valid.test(s)) {
                    return s;
                }
            }
            invalid(path, value, accepted, "\"" + def + "\"");
            return def;
        }

        <E extends Enum<E>> E choice(String path, Class<E> type, E def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            if (value instanceof String s) {
                for (E option : type.getEnumConstants()) {
                    if (option.name().equalsIgnoreCase(s.strip())) {
                        return option;
                    }
                }
            }
            String options = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(" or "));
            invalid(path, value, options, def);
            return def;
        }

        /** A money amount of 0 or more, as a decimal number. */
        BigDecimal amount(String path, BigDecimal def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            BigDecimal amount = toDecimal(value);
            if (amount != null && amount.signum() >= 0) {
                return amount;
            }
            invalid(path, value, "a number of at least 0", def);
            return def;
        }

        private static BigDecimal toDecimal(Object value) {
            if (value instanceof Integer || value instanceof Long) {
                return BigDecimal.valueOf(((Number) value).longValue());
            }
            if (value instanceof Double || value instanceof Float) {
                double d = ((Number) value).doubleValue();
                return Double.isFinite(d) ? new BigDecimal(value.toString()) : null;
            }
            return null;
        }

        List<BigDecimal> moneyButtons(String path, List<BigDecimal> def) {
            List<?> list = buttonList(path, def);
            if (list == null) {
                return def;
            }
            List<BigDecimal> buttons = new ArrayList<>();
            for (Object entry : list) {
                BigDecimal amount = toDecimal(entry);
                if (amount == null || amount.signum() <= 0) {
                    invalid(path, list, "1 to " + MAX_BUTTONS + " positive numbers", def);
                    return def;
                }
                buttons.add(amount);
            }
            buttons.sort(null);
            return buttons;
        }

        List<Integer> xpButtons(String path, List<Integer> def) {
            List<?> list = buttonList(path, def);
            if (list == null) {
                return def;
            }
            List<Integer> buttons = new ArrayList<>();
            for (Object entry : list) {
                if (!(entry instanceof Integer n) || n <= 0) {
                    invalid(path, list, "1 to " + MAX_BUTTONS + " positive whole numbers", def);
                    return def;
                }
                buttons.add(n);
            }
            buttons.sort(null);
            return buttons;
        }

        /** The raw button list if it has 1-4 entries, else null (after a warning). */
        private List<?> buttonList(String path, Object def) {
            Object value = value(path, def);
            if (value == null) {
                return null;
            }
            if (value instanceof List<?> list && !list.isEmpty() && list.size() <= MAX_BUTTONS) {
                return list;
            }
            invalid(path, value, "a list of 1 to " + MAX_BUTTONS + " amounts", def);
            return null;
        }

        Set<String> worlds(String path, Set<String> def) {
            Object value = value(path, "[]");
            if (value == null) {
                return def;
            }
            if (!(value instanceof List<?> list)) {
                invalid(path, value, "a list of world names", "[]");
                return def;
            }
            Set<String> worlds = new HashSet<>();
            for (Object entry : list) {
                if (!isScalar(entry)) {
                    warnings.add(path + ": '" + entry + "' is not a world name, skipped");
                    continue;
                }
                worlds.add(entry.toString().strip().toLowerCase(Locale.ROOT));
            }
            return worlds;
        }

        Set<GameMode> gameModes(String path, Set<GameMode> def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            String options = Arrays.stream(GameMode.values()).map(Enum::name).collect(Collectors.joining(", "));
            if (!(value instanceof List<?> list)) {
                invalid(path, value, "a list of game modes (" + options + ")", def);
                return def;
            }
            Set<GameMode> modes = new HashSet<>();
            for (Object entry : list) {
                GameMode mode = null;
                if (entry instanceof String s) {
                    for (GameMode m : GameMode.values()) {
                        if (m.name().equalsIgnoreCase(s.strip())) {
                            mode = m;
                        }
                    }
                }
                if (mode == null) {
                    warnings.add(path + ": '" + entry + "' is not a game mode (" + options + "), skipped");
                } else {
                    modes.add(mode);
                }
            }
            return modes;
        }

        Map<String, String> properties(String path, Map<String, String> def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            if (!(value instanceof ConfigurationSection section)) {
                invalid(path, value, "a list of name: value pairs", def);
                return def;
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                Object entry = section.get(key);
                if (isScalar(entry)) {
                    properties.put(key, entry.toString());
                } else {
                    warnings.add(path + "." + key + ": '" + entry + "' is not a single value, skipped");
                }
            }
            return properties;
        }

        SoundSetting sound(String section, SoundSetting def) {
            boolean enabled = bool(section + ".enabled", def.enabled());
            NamespacedKey key = soundKey(section + ".sound", def.sound());
            float volume = decimal(section + ".volume", 0.0, 10.0, def.volume());
            float pitch = decimal(section + ".pitch", 0.5, 2.0, def.pitch());
            return new SoundSetting(enabled, key, volume, pitch);
        }

        private NamespacedKey soundKey(String path, NamespacedKey def) {
            Object value = value(path, def);
            if (value == null) {
                return def;
            }
            NamespacedKey key = value instanceof String s ? NamespacedKey.fromString(s.strip()) : null;
            if (key == null) {
                invalid(path, value, "a sound key like minecraft:block.note_block.pling", def);
                return def;
            }
            if (!soundExists.test(key)) {
                invalid(path, value, "a known sound", def);
                return def;
            }
            return key;
        }

        void warnUnknownKeys() {
            List<String> unknown = new ArrayList<>();
            for (String path : yaml.getKeys(true)) {
                boolean known = KNOWN_KEYS.stream().anyMatch(k -> k.equals(path) || k.startsWith(path + "."));
                boolean free = FREE_SECTIONS.stream().anyMatch(f -> path.startsWith(f + "."));
                boolean underUnknown = unknown.stream().anyMatch(u -> path.startsWith(u + "."));
                if (!known && !free && !underUnknown) {
                    unknown.add(path);
                    warnings.add(path + " is not a known setting (typo?), ignored");
                }
            }
        }
    }
}
