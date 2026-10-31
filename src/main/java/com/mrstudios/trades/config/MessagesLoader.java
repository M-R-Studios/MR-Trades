package com.mrstudios.trades.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link Messages} from {@code messages.yml}. A missing key, or a message with a tag
 * MiniMessage can't use, falls back to the text bundled in the jar with a warning naming the key.
 */
public final class MessagesLoader {

    private static final String VERSION_KEY = "messages-version";

    private static final Set<String> PLAYER = Set.of("player");
    private static final Set<String> COUNT = Set.of("count");

    /**
     * Placeholders each message may use besides {@code <prefix>} and the standard MiniMessage tags.
     * Messages not listed take none.
     */
    private static final Map<String, Set<String>> PLACEHOLDERS = Map.ofEntries(
            Map.entry("request-sent", PLAYER),
            Map.entry("request-received", PLAYER),
            // <accept>/<deny> are click styles filled in by code: MiniMessage doesn't replace
            // placeholders inside a <click:...> argument, so the command can't be written here.
            Map.entry("request-buttons", Set.of("player", "accept", "deny")),
            Map.entry("request-expired-sender", PLAYER),
            Map.entry("request-expired-target", PLAYER),
            Map.entry("request-denied", PLAYER),
            Map.entry("request-denied-self", PLAYER),
            Map.entry("request-already-sent", PLAYER),
            Map.entry("no-request", PLAYER),
            Map.entry("cooldown", Set.of("remaining")),
            Map.entry("target-offline", PLAYER),
            Map.entry("target-busy", PLAYER),
            Map.entry("target-toggled", PLAYER),
            Map.entry("too-far", Set.of("player", "distance")),
            Map.entry("other-world", PLAYER),
            Map.entry("blocked-gamemode", Set.of("gamemode")),
            Map.entry("target-blocked-gamemode", PLAYER),
            Map.entry("trade-started", PLAYER),
            Map.entry("confirm-locked", PLAYER),
            Map.entry("countdown", Set.of("seconds")),
            Map.entry("trade-complete", PLAYER),
            Map.entry("trade-cancelled", Set.of("reason")),
            Map.entry("complete-failed-space", PLAYER),
            Map.entry("complete-failed-money", PLAYER),
            Map.entry("complete-failed-xp", PLAYER),
            Map.entry("economy-error", Set.of("id")),
            Map.entry("reason-closed", PLAYER),
            Map.entry("reason-cancelled", PLAYER),
            Map.entry("reason-quit", PLAYER),
            Map.entry("reason-died", PLAYER),
            Map.entry("reason-world", PLAYER),
            Map.entry("reason-gamemode", PLAYER),
            Map.entry("input-chat-prompt", Set.of("cancel-word", "seconds")),
            Map.entry("input-invalid", Set.of("input", "cancel-word")),
            Map.entry("input-too-high", Set.of("max")),
            Map.entry("form-request-content", PLAYER),
            Map.entry("returns-queued", COUNT),
            Map.entry("returns-dropped", COUNT),
            Map.entry("returns-waiting", COUNT),
            Map.entry("returns-claimed", Set.of("count", "left")),
            Map.entry("history-unknown-player", PLAYER),
            Map.entry("gui.trade-title", PLAYER),
            Map.entry("gui.history-title", Set.of("player", "page")),
            Map.entry("gui.head-you", PLAYER),
            Map.entry("gui.head-other", PLAYER),
            Map.entry("gui.status-waiting", PLAYER),
            Map.entry("gui.status-countdown", Set.of("seconds")),
            Map.entry("gui.money", Set.of("amount")),
            Map.entry("gui.xp", Set.of("amount")),
            Map.entry("gui.other-confirmed", PLAYER),
            Map.entry("gui.other-not-confirmed", PLAYER),
            Map.entry("gui.editor-plus", Set.of("amount")),
            Map.entry("gui.editor-minus", Set.of("amount")),
            Map.entry("gui.editor-current", Set.of("amount")),
            Map.entry("gui.editor-max", Set.of("amount")),
            Map.entry("gui.history-entry", PLAYER),
            Map.entry("gui.history-entry-lore", Set.of("player", "date", "gave", "got")),
            Map.entry("version", Set.of("version", "server", "hooks", "storage")),
            Map.entry("admin-cancelled", PLAYER),
            Map.entry("admin-not-trading", PLAYER),
            Map.entry("reload-warnings", COUNT),
            Map.entry("reload-failed", Set.of("file")),
            Map.entry("reload-detail", Set.of("detail")));

    /** Parses with nothing but the resolver passed in, so {@link TagChecker} sees every tag. */
    private static final MiniMessage VALIDATOR = MiniMessage.builder().tags(TagResolver.empty()).build();

    private final YamlConfiguration defaults;
    private final ConfigMigrator migrator = ConfigMigrator.forMessages();

    /** @param defaults the {@code messages.yml} bundled in the jar */
    public MessagesLoader(YamlConfiguration defaults) {
        this.defaults = defaults;
    }

    public static YamlConfiguration bundledDefaults() {
        try (InputStream in = MessagesLoader.class.getResourceAsStream("/messages.yml")) {
            if (in == null) {
                throw new IllegalStateException("messages.yml is missing from the jar");
            }
            return YamlFiles.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled messages.yml");
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException("Could not read the bundled messages.yml", e);
        }
    }

    /** The bundled messages, used when {@code messages.yml} can't be loaded at startup. */
    public Messages defaults() {
        return read(defaults).value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<Messages> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<Messages> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<Messages> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (key.equals(VERSION_KEY) || defaults.isConfigurationSection(key)) {
                continue;
            }
            if (defaults.isList(key)) {
                lists.put(key, readLines(yaml, key, warnings));
            } else {
                texts.put(key, readText(yaml, key, warnings));
            }
        }
        for (String key : yaml.getKeys(true)) {
            if (defaults.contains(key)) {
                continue;
            }
            int dot = key.lastIndexOf('.');
            // Below an unknown key or a known message: already reported once.
            if (dot >= 0 && !defaults.isConfigurationSection(key.substring(0, dot))) {
                continue;
            }
            warnings.add(key + " is not a known message (typo?), ignored");
        }
        return LoadResult.loaded(new Messages(texts, lists), warnings);
    }

    private String readText(YamlConfiguration yaml, String key, List<String> warnings) {
        String def = defaults.getString(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof String text)) {
            warnings.add(key + ": '" + value + "' is not text, using the default");
            return def;
        }
        String problem = problem(key, text);
        if (problem != null) {
            warnings.add(key + ": " + problem + ", using the default");
            return def;
        }
        return text;
    }

    private List<String> readLines(YamlConfiguration yaml, String key, List<String> warnings) {
        List<String> def = defaults.getStringList(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof List<?> list)) {
            warnings.add(key + " is not a list of lines, using the default");
            return def;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String problem = list.get(i) instanceof String line ? problem(key, line) : "is not text";
            if (problem != null) {
                warnings.add(key + " line " + (i + 1) + ": " + problem + ", using the default " + key);
                return def;
            }
            lines.add((String) list.get(i));
        }
        return lines;
    }

    /** Why {@code text} can't be used for {@code key}, or null if it's fine. */
    private static String problem(String key, String text) {
        List<TagResolver> known = new ArrayList<>();
        known.add(TagResolver.standard());
        if (!key.equals("prefix")) {
            known.add(Placeholder.unparsed("prefix", ""));
        }
        for (String name : PLACEHOLDERS.getOrDefault(key, Set.of())) {
            known.add(Placeholder.unparsed(name, ""));
        }
        TagChecker checker = new TagChecker(TagResolver.resolver(known));
        try {
            VALIDATOR.deserialize(text, checker);
        } catch (RuntimeException e) {
            return "is not valid MiniMessage (" + e.getMessage() + ")";
        }
        return checker.problem;
    }

    /**
     * Sees every tag in a message and records the first one that can't be used: an unknown name, a
     * placeholder from another message, or a standard tag with bad arguments. Escaped tags
     * ({@code \<player>}) are plain text and never reach it.
     */
    private static final class TagChecker implements TagResolver {

        private final TagResolver known;
        private String problem;

        TagChecker(TagResolver known) {
            this.known = known;
        }

        @Override
        public Tag resolve(String name, ArgumentQueue arguments, Context ctx) {
            if (known.has(name)) {
                try {
                    Tag tag = known.resolve(name, arguments, ctx);
                    if (tag != null) {
                        return tag;
                    }
                } catch (ParsingException e) {
                    // Falls through: reported below.
                }
                arguments.reset();
                record(name, arguments, " has invalid arguments");
            } else {
                record(name, arguments, " is not a valid tag in this message");
            }
            return Tag.selfClosingInserting(Component.empty());
        }

        private void record(String name, ArgumentQueue arguments, String reason) {
            if (problem != null) {
                return;
            }
            StringBuilder tag = new StringBuilder("<").append(name);
            while (arguments.hasNext()) {
                tag.append(':').append(arguments.pop().value());
            }
            problem = tag.append('>').append(reason).toString();
        }

        @Override
        public boolean has(String name) {
            return true;
        }
    }
}
