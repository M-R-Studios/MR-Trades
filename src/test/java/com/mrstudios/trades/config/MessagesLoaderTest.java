package com.mrstudios.trades.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessagesLoaderTest {

    private final YamlConfiguration bundled = MessagesLoader.bundledDefaults();
    private final MessagesLoader loader = new MessagesLoader(bundled);

    private String bundledWith(String key, Object value) {
        YamlConfiguration yaml = copyOfBundled();
        yaml.set(key, value);
        return yaml.saveToString();
    }

    private YamlConfiguration copyOfBundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        bundled.getValues(true).forEach((key, value) -> {
            if (!bundled.isConfigurationSection(key)) {
                yaml.set(key, value);
            }
        });
        return yaml;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void bundledMessagesLoadWithoutWarnings() {
        LoadResult<Messages> result = loader.load(bundled.saveToString());

        assertTrue(result.ok(), result.error());
        assertEquals(List.of(), result.warnings());
        assertEquals(6, result.value().rawLines("help").size());
        assertEquals(3, result.value().rawLines("admin-help").size());
        assertEquals(3, result.value().rawLines("gui.history-entry-lore").size());
    }

    @Test
    void escapedTagsInHelpRenderAsText() {
        Messages messages = loader.defaults();

        assertEquals("MR-Trades » /trade <player> - send a trade request",
                plain(messages.components("help").get(0)));
        assertTrue(plain(messages.components("admin-help").get(2)).contains("/mrtrades cancel <player>"));
    }

    @Test
    void requestButtonsTakeClickStylesFromCode() {
        Component buttons = loader.defaults().component("request-buttons",
                Placeholder.styling("accept", ClickEvent.runCommand("/trade accept Alex")),
                Placeholder.styling("deny", ClickEvent.runCommand("/trade deny Alex")));

        assertEquals("[Accept] [Deny]", plain(buttons));
        List<String> commands = new ArrayList<>();
        buttons.iterable(ComponentIteratorType.DEPTH_FIRST).forEach(part -> {
            if (part.clickEvent() != null && !commands.contains(part.clickEvent().value())) {
                commands.add(part.clickEvent().value());
            }
        });
        assertEquals(List.of("/trade accept Alex", "/trade deny Alex"), commands);
    }

    @Test
    void nestedGuiMessagesAreReadByFullPath() {
        Messages messages = loader.load(bundledWith("gui.trade-title", "Swap with <player>")).value();

        assertEquals("Swap with Alex", plain(messages.component("gui.trade-title", Placeholder.unparsed("player", "Alex"))));
        assertEquals(bundled.getString("gui.cancel"), messages.raw("gui.cancel"));
    }

    @Test
    void missingKeyFallsBackWithWarning() {
        LoadResult<Messages> result = loader.load(bundledWith("cooldown", null));

        assertEquals(List.of("cooldown is missing, using the default"), result.warnings());
        assertEquals(bundled.getString("cooldown"), result.value().raw("cooldown"));
    }

    @Test
    void missingNestedKeyFallsBackWithWarning() {
        LoadResult<Messages> result = loader.load(bundledWith("gui.confirm", null));

        assertEquals(List.of("gui.confirm is missing, using the default"), result.warnings());
        assertEquals(bundled.getString("gui.confirm"), result.value().raw("gui.confirm"));
    }

    @Test
    void customMessageIsUsedAndPlaceholdersAreNotParsed() {
        LoadResult<Messages> result = loader.load(bundledWith("cooldown", "<prefix>Wait <remaining> seconds"));

        assertEquals(List.of(), result.warnings());
        Component message = result.value().component("cooldown", Placeholder.unparsed("remaining", "<red>2"));
        assertEquals("MR-Trades » Wait <red>2 seconds", plain(message));
    }

    @Test
    void unknownTagFallsBackWithWarningNamingTheKey() {
        LoadResult<Messages> result = loader.load(bundledWith("no-permission", "<prefix><bogus>Nope"));

        assertEquals(1, result.warnings().size());
        String warning = result.warnings().get(0);
        assertTrue(warning.startsWith("no-permission: <bogus> is not a valid tag"), warning);
        assertEquals(bundled.getString("no-permission"), result.value().raw("no-permission"));
    }

    @Test
    void malformedTagFallsBackWithWarning() {
        LoadResult<Messages> result = loader.load(bundledWith("disabled-world", "<gradient:nope:nope>No"));

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("disabled-world: <gradient:nope:nope> has invalid arguments"),
                result.warnings().get(0));
        assertEquals(bundled.getString("disabled-world"), result.value().raw("disabled-world"));
    }

    @Test
    void placeholderFromAnotherMessageIsRejected() {
        LoadResult<Messages> result = loader.load(bundledWith("no-permission", "<red>Wait <remaining>s"));

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("no-permission: <remaining>"), result.warnings().get(0));
    }

    @Test
    void emptyMessageIsAllowed() {
        LoadResult<Messages> result = loader.load(bundledWith("reload-success", ""));

        assertEquals(List.of(), result.warnings());
        assertEquals("", result.value().raw("reload-success"));
    }

    @Test
    void nonTextAndUnknownKeysWarn() {
        YamlConfiguration yaml = copyOfBundled();
        yaml.set("player-only", 5);
        yaml.set("no-permision", "typo");
        yaml.set("gui.cancle", "typo");
        yaml.set("extra.a", "x");
        yaml.set("extra.b", "y");

        LoadResult<Messages> result = loader.load(yaml.saveToString());

        assertEquals(List.of(
                "player-only: '5' is not text, using the default",
                "gui.cancle is not a known message (typo?), ignored",
                "no-permision is not a known message (typo?), ignored",
                "extra is not a known message (typo?), ignored"), result.warnings());
    }

    @Test
    void badListLineFallsBackToDefaultList() {
        LoadResult<Messages> result = loader.load(bundledWith("admin-help", List.of("<prefix>ok", "<nope>")));

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("admin-help line 2: "), result.warnings().get(0));
        assertEquals(bundled.getStringList("admin-help"), result.value().rawLines("admin-help"));
    }

    @Test
    void listMessageThatIsNotAListFallsBack() {
        LoadResult<Messages> result = loader.load(bundledWith("gui.history-entry-lore", "one line"));

        assertEquals(List.of("gui.history-entry-lore is not a list of lines, using the default"), result.warnings());
    }

    @Test
    void loreLinesRenderWithPlaceholders() {
        List<Component> lore = loader.defaults().components("gui.history-entry-lore",
                Placeholder.unparsed("date", "today"), Placeholder.unparsed("gave", "1x Diamond"),
                Placeholder.unparsed("got", "nothing"));

        assertEquals(List.of("today", "You gave: 1x Diamond", "You got: nothing"),
                lore.stream().map(MessagesLoaderTest::plain).toList());
    }

    @Test
    void yamlSyntaxErrorFails() {
        LoadResult<Messages> result = loader.load("prefix: \"unclosed\n");

        assertFalse(result.ok());
        assertTrue(result.error().startsWith("messages.yml line "), result.error());
    }

    @Test
    void newerMessagesVersionFails() {
        LoadResult<Messages> result = loader.load(bundledWith("messages-version", 2));

        assertFalse(result.ok());
        assertTrue(result.error().contains("newer MR-Trades"), result.error());
    }

    @Test
    void prefixCannotReferToItself() {
        LoadResult<Messages> result = loader.load(bundledWith("prefix", "<prefix> "));

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).startsWith("prefix: <prefix>"), result.warnings().get(0));
    }
}
