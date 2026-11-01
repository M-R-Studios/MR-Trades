package com.mrstudios.trades.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AdminCommandTest {

    private ServerMock server;
    private MRTrades plugin;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRTrades.class);
        admin = server.addPlayer();
        admin.setOp(true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private List<String> messages() {
        List<String> lines = new ArrayList<>();
        Component message;
        while ((message = admin.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return lines;
    }

    private void write(String file, String text) throws Exception {
        Files.writeString(new File(plugin.getDataFolder(), file).toPath(), text, StandardCharsets.UTF_8);
    }

    private String read(String file) throws Exception {
        return Files.readString(new File(plugin.getDataFolder(), file).toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void enableWritesBothFilesAndUsesDefaults() {
        assertTrue(new File(plugin.getDataFolder(), "config.yml").exists());
        assertTrue(new File(plugin.getDataFolder(), "messages.yml").exists());
        assertEquals(Settings.DEFAULTS, plugin.state().settings());
    }

    @Test
    void helpListsAdminCommands() {
        admin.performCommand("mrtrades");

        List<String> lines = messages();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("/mrtrades reload"), lines.get(0));
    }

    @Test
    void aliasWorks() {
        admin.performCommand("mrt version");

        String text = messages().get(0);
        assertTrue(text.contains("MR-Trades"), text);
        assertTrue(text.contains("Hooks: none"), text);
        assertTrue(text.contains("Storage: SQLITE - "), text);
    }

    @Test
    void reloadWithUnchangedFilesSucceeds() {
        admin.performCommand("mrtrades reload");

        assertEquals(List.of("MR-Trades » Reloaded config.yml and messages.yml."), messages());
    }

    @Test
    void reloadAppliesNewValues() throws Exception {
        write("config.yml", read("config.yml").replace("max-distance: 16", "max-distance: 32"));

        admin.performCommand("mrtrades reload");

        assertEquals(32, plugin.state().settings().limits().maxDistance());
        assertEquals(List.of("MR-Trades » Reloaded config.yml and messages.yml."), messages());
    }

    @Test
    void brokenConfigKeepsPreviousSettingsAndSaysWhere() throws Exception {
        Settings before = plugin.state().settings();
        write("config.yml", read("config.yml").replace("max-distance: 16", "max-distance: 16\n bad: x: y"));

        admin.performCommand("mrtrades reload");

        assertSame(before, plugin.state().settings());
        List<String> lines = messages();
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("MR-Trades » Reload failed for config.yml; kept the previous settings for it:", lines.get(0));
        assertTrue(lines.get(1).startsWith(" - config.yml line "), lines.get(1));
    }

    @Test
    void badValueIsReportedAsWarningNamingTheKey() throws Exception {
        write("config.yml", read("config.yml").replace("countdown-seconds: 3", "countdown-seconds: 99"));

        admin.performCommand("mrtrades reload");

        List<String> lines = messages();
        assertEquals("MR-Trades » Reloaded with 1 warning(s):", lines.get(0));
        assertTrue(lines.get(1).startsWith(" - config.yml: trade.countdown-seconds: '99'"), lines.get(1));
        assertEquals(3, plugin.state().settings().trade().countdownSeconds());
    }

    @Test
    void brokenMessagesKeepPreviousMessagesButConfigStillReloads() throws Exception {
        write("config.yml", read("config.yml").replace("max-distance: 16", "max-distance: 20"));
        write("messages.yml", "prefix: \"unclosed\n");

        admin.performCommand("mrtrades reload");

        assertEquals(20, plugin.state().settings().limits().maxDistance());
        List<String> lines = messages();
        assertEquals("MR-Trades » Reload failed for messages.yml; kept the previous settings for it:", lines.get(0));
        assertTrue(lines.get(1).startsWith(" - messages.yml line "), lines.get(1));
    }

    @Test
    void storageChangeSaysRestartIsNeeded() throws Exception {
        write("config.yml", read("config.yml").replace("type: SQLITE", "type: MYSQL"));

        admin.performCommand("mrtrades reload");

        List<String> lines = messages();
        assertTrue(lines.contains(" - config.yml: storage changes need a restart; the storage in use was not changed."),
                lines.toString());
        assertEquals(Settings.StorageType.SQLITE, plugin.startupStorage().type());
    }

    @Test
    void playersWithoutPermissionAreRefused() {
        PlayerMock player = server.addPlayer();

        player.performCommand("mrtrades reload");

        assertEquals("MR-Trades » You don't have permission to do that.",
                PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage()));
        assertNull(player.nextComponentMessage());
    }

    @Test
    void tabCompletesSubcommands() {
        assertEquals(List.of("reload"), server.getCommandTabComplete(admin, "mrtrades re"));
        assertEquals(List.of("reload", "version", "cancel"), server.getCommandTabComplete(admin, "mrtrades "));
    }
}
