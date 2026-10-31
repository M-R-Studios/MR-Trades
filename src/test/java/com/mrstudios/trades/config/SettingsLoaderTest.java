package com.mrstudios.trades.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.config.Settings.StorageType;
import com.mrstudios.trades.config.Settings.WhenFull;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettingsLoaderTest {

    private static final Set<NamespacedKey> SOUNDS = Set.of(
            NamespacedKey.minecraft("entity.experience_orb.pickup"),
            NamespacedKey.minecraft("block.chest.open"),
            NamespacedKey.minecraft("ui.button.click"),
            NamespacedKey.minecraft("block.note_block.pling"),
            NamespacedKey.minecraft("block.note_block.hat"),
            NamespacedKey.minecraft("entity.player.levelup"),
            NamespacedKey.minecraft("entity.villager.no"),
            NamespacedKey.minecraft("block.anvil.use"));

    private final SettingsLoader loader = new SettingsLoader(SOUNDS::contains);

    static String bundledConfig() throws IOException {
        try (InputStream in = SettingsLoaderTest.class.getResourceAsStream("/config.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(bundledConfig());
        return yaml;
    }

    private static String bundledWith(String path, Object value) throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set(path, value);
        return yaml.saveToString();
    }

    @Test
    void bundledConfigLoadsWithoutWarningsAndMatchesDefaults() throws IOException {
        LoadResult<Settings> result = loader.load(bundledConfig());

        assertTrue(result.ok(), result.error());
        assertEquals(List.of(), result.warnings());
        assertEquals(Settings.DEFAULTS, result.value());
    }

    @Test
    void everyBundledSettingIsCommented() throws Exception {
        // CLAUDE.md: every config option is commented in config.yml.
        YamlConfiguration yaml = bundled();
        for (String path : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(path) || path.startsWith("sounds.") || path.startsWith("storage.mysql.") || path.contains(".properties")) {
                continue; // Documented once for the whole section.
            }
            assertFalse(yaml.getComments(path).isEmpty(), path + " has no comment");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "requests.timeout-seconds       | 4                | at least 5",
        "requests.timeout-seconds       | soon             | at least 5",
        "requests.cooldown-seconds      | -1               | at least 0",
        "requests.cooldown-seconds      | 1.5              | at least 0",
        "requests.sneak-right-click     | maybe            | true or false",
        "requests.clickable-buttons     | 3                | true or false",
        "limits.max-distance            | -1               | at least 0",
        "limits.distance-check-ticks    | 4                | 5 to 100",
        "limits.distance-check-ticks    | 101              | 5 to 100",
        "limits.allow-cross-world       | yes please       | true or false",
        "limits.disabled-worlds         | world            | a list of world names",
        "limits.blocked-gamemodes       | CREATIVE         | a list of game modes",
        "trade.countdown-seconds        | 11               | 0 to 10",
        "trade.change-lock-ms           | -5               | at least 0",
        "money.max-amount               | -1               | at least 0",
        "money.max-amount               | lots             | at least 0",
        "money.buttons                  | 10               | a list of 1 to 4",
        "input.chat-timeout-seconds     | 2                | at least 5",
        "input.cancel-word              | stop it          | a single word",
        "returns.when-full              | DELETE           | QUEUE or DROP",
        "history.retention-days         | -1               | at least 0",
        "storage.type                   | POSTGRES         | SQLITE or MYSQL",
        "storage.sqlite.file            | ../history.db    | a file name without folders",
        "storage.mysql.port             | 70000            | 1 to 65535",
        "storage.mysql.table-prefix     | trades-          | letters, digits and _ only",
        "storage.mysql.pool-size        | 0                | 1 to 20",
        "storage.mysql.pool-size        | 21               | 1 to 20",
        "storage.mysql.host             | ''               | a host name",
        "sounds.request.sound           | not a key!       | a sound key",
        "sounds.cancel.sound            | minecraft:nope   | a known sound",
        "sounds.open.volume             | 11               | 0.0 to 10.0",
        "sounds.confirm.pitch           | 0.1              | 0.5 to 2.0",
        "sounds.countdown.enabled       | sometimes        | true or false",
    })
    void invalidValueFallsBackToDefaultWithWarning(String path, String bad, String accepted) throws Exception {
        Object value = parseScalar(bad);
        LoadResult<Settings> result = loader.load(bundledWith(path, value));

        assertTrue(result.ok(), result.error());
        assertEquals(Settings.DEFAULTS, result.value());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        String warning = result.warnings().get(0);
        assertTrue(warning.startsWith(path + ": '" + value + "'"), warning);
        assertTrue(warning.contains(accepted), warning);
        assertTrue(warning.contains("using "), warning);
    }

    /** Lets the CSV rows above produce YAML numbers as well as strings. */
    private static Object parseScalar(String s) throws InvalidConfigurationException {
        if (s.isEmpty()) {
            return "";
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("v: " + (s.contains("!") || s.startsWith("..") ? "'" + s + "'" : s));
        return yaml.get("v");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "money.buttons | [] ",
        "money.buttons | [1, 2, 3, 4, 5]",
        "money.buttons | [1, 0]",
        "money.buttons | [1, -2]",
        "money.buttons | [1, ten]",
        "xp.buttons    | [1, 2.5]",
        "xp.buttons    | [0]",
    })
    void invalidButtonsFallBackToDefaultWithWarning(String path, String bad) throws Exception {
        LoadResult<Settings> result = loader.load(bundledWith(path, parseScalar(bad)));

        assertEquals(Settings.DEFAULTS, result.value());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().get(0).startsWith(path + ": "), result.warnings().get(0));
    }

    @Test
    void validNonDefaultValuesAreRead() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("requests.timeout-seconds", 60);
        yaml.set("requests.cooldown-seconds", 0);
        yaml.set("limits.max-distance", 0);
        yaml.set("limits.disabled-worlds", List.of("World_Nether", "Spawn"));
        yaml.set("limits.blocked-gamemodes", List.of("adventure", "Spectator"));
        yaml.set("limits.allow-cross-world", true);
        yaml.set("trade.countdown-seconds", 0);
        yaml.set("money.buttons", List.of(500, 0.5, 25));
        yaml.set("money.max-amount", 1000000.5);
        yaml.set("xp.buttons", List.of(10, 1));
        yaml.set("input.cancel-word", " Stop ");
        yaml.set("returns.when-full", "drop");
        yaml.set("history.server-name", "survival");
        yaml.set("storage.type", "mysql");
        yaml.set("storage.mysql.password", 1234);
        yaml.set("storage.mysql.properties", Map.of("useSSL", false, "connectTimeout", 5000));
        yaml.set("sounds.complete.sound", "block.anvil.use");
        yaml.set("sounds.complete.volume", 2);

        LoadResult<Settings> result = loader.load(yaml.saveToString());

        assertEquals(List.of(), result.warnings());
        Settings settings = result.value();
        assertEquals(60, settings.requests().timeoutSeconds());
        assertEquals(0, settings.requests().cooldownSeconds());
        assertEquals(0, settings.limits().maxDistance());
        assertEquals(Set.of("world_nether", "spawn"), settings.limits().disabledWorlds());
        assertTrue(settings.limits().isWorldDisabled("WORLD_NETHER"));
        assertFalse(settings.limits().isWorldDisabled("world"));
        assertEquals(Set.of(GameMode.ADVENTURE, GameMode.SPECTATOR), settings.limits().blockedGameModes());
        assertTrue(settings.limits().allowCrossWorld());
        assertEquals(0, settings.trade().countdownSeconds());
        assertEquals(List.of(new BigDecimal("0.5"), BigDecimal.valueOf(25), BigDecimal.valueOf(500)),
                settings.money().buttons());
        assertEquals(new BigDecimal("1000000.5"), settings.money().maxAmount());
        assertEquals(List.of(1, 10), settings.xp().buttons());
        assertEquals("Stop", settings.input().cancelWord());
        assertEquals(WhenFull.DROP, settings.returns().whenFull());
        assertEquals("survival", settings.history().effectiveServerName());
        assertEquals(StorageType.MYSQL, settings.storage().type());
        assertEquals("1234", settings.storage().mysql().password());
        assertEquals(Map.of("useSSL", "false", "connectTimeout", "5000"), settings.storage().mysql().properties());
        assertEquals(NamespacedKey.minecraft("block.anvil.use"), settings.sounds().complete().sound());
        assertEquals(2.0f, settings.sounds().complete().volume());
    }

    @Test
    void emptyServerNameMeansDefault() {
        assertEquals("default", Settings.DEFAULTS.history().effectiveServerName());
    }

    @Test
    void unknownGameModeIsSkippedWithWarning() throws Exception {
        LoadResult<Settings> result = loader.load(bundledWith("limits.blocked-gamemodes", List.of("CREATIVE", "GOD")));

        assertEquals(Set.of(GameMode.CREATIVE), result.value().limits().blockedGameModes());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("limits.blocked-gamemodes: 'GOD' is not a game mode"),
                result.warnings().get(0));
    }

    @Test
    void unknownKeysWarnOncePerUnknownSectionButPropertiesAreFree() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("limits.max-distanse", 10);
        yaml.set("extra.a", 1);
        yaml.set("extra.b", 2);
        yaml.set("storage.mysql.properties.useUnicode", true);

        LoadResult<Settings> result = loader.load(yaml.saveToString());

        assertTrue(result.ok());
        assertEquals(List.of(
                "limits.max-distanse is not a known setting (typo?), ignored",
                "extra is not a known setting (typo?), ignored"), result.warnings());
    }

    @Test
    void missingKeyFallsBackWithWarning() throws Exception {
        LoadResult<Settings> result = loader.load(bundledWith("requests.cooldown-seconds", null));

        assertEquals(Settings.DEFAULTS, result.value());
        assertEquals(List.of("requests.cooldown-seconds is missing, using 5"), result.warnings());
    }

    @Test
    void passwordIsNotInTheSettingsText() throws Exception {
        LoadResult<Settings> result = loader.load(bundledWith("storage.mysql.password", "hunter2"));

        assertEquals("hunter2", result.value().storage().mysql().password());
        assertFalse(result.value().toString().contains("hunter2"));
    }

    @Test
    void yamlSyntaxErrorFailsWithLineNumber() {
        LoadResult<Settings> result = loader.load("config-version: 1\nlimits:\n  max-distance: 5\n bad: x: y\n");

        assertFalse(result.ok());
        assertNull(result.value());
        assertTrue(result.error().startsWith("config.yml line 4, column 2: "), result.error());
    }

    @Test
    void newerConfigVersionFails() throws Exception {
        LoadResult<Settings> result = loader.load(bundledWith("config-version", 99));

        assertFalse(result.ok());
        assertTrue(result.error().contains("newer MR-Trades"), result.error());
    }
}
