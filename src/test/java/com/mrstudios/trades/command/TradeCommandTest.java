package com.mrstudios.trades.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.MRTrades;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /trade} requests end to end on a mock server (DESIGN.md step 3). */
class TradeCommandTest {

    private ServerMock server;
    private MRTrades plugin;
    private World world;
    private PlayerMock alex;
    private PlayerMock sam;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRTrades.class);
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        sam = server.addPlayer("Sam");
        alex.teleport(new Location(world, 0, 64, 0));
        sam.teleport(new Location(world, 3, 64, 0));
        drain(alex);
        drain(sam);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return lines;
    }

    private static String only(PlayerMock player) {
        List<String> lines = drain(player);
        assertEquals(1, lines.size(), lines.toString());
        return lines.get(0);
    }

    private int pending() {
        return plugin.requests().requests().size();
    }

    @Test
    void requestReachesTheTargetWithCommandsAndButtons() {
        alex.performCommand("trade Sam");

        assertEquals("MR-Trades » Trade request sent to Sam.", only(alex));
        assertEquals(2, drain(sam).size(), "request-received and request-buttons");
        assertEquals(1, pending());
    }

    @Test
    void buttonsRunTheAcceptAndDenyCommands() {
        alex.performCommand("trade Sam");
        Component text = sam.nextComponentMessage();
        Component buttons = sam.nextComponentMessage();

        assertEquals("MR-Trades » Alex wants to trade. Type /trade accept Alex or /trade deny Alex.",
                PlainTextComponentSerializer.plainText().serialize(text));
        List<String> commands = new ArrayList<>();
        buttons.iterable(ComponentIteratorType.DEPTH_FIRST).forEach(part -> {
            if (part.clickEvent() != null && !commands.contains(part.clickEvent().value())) {
                commands.add(part.clickEvent().value());
            }
        });
        assertEquals(List.of("/trade accept Alex", "/trade deny Alex"), commands);
    }

    @Test
    void buttonsCanBeTurnedOff() throws Exception {
        java.nio.file.Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        java.nio.file.Files.writeString(config, java.nio.file.Files.readString(config)
                .replace("clickable-buttons: true", "clickable-buttons: false"));
        plugin.reload(server.getConsoleSender());

        alex.performCommand("trade Sam");

        assertTrue(only(sam).contains("/trade accept Alex"), "the typed commands are always shown");
    }

    @Test
    void acceptRemovesTheRequest() {
        alex.performCommand("trade Sam");
        drain(sam);

        sam.performCommand("trade accept");

        assertEquals(0, pending());
        assertEquals(List.of(), drain(sam), "nothing opens yet in step 3");
    }

    @Test
    void acceptByNameIsCaseInsensitive() {
        alex.performCommand("trade Sam");
        sam.performCommand("trade accept alex");
        assertEquals(0, pending());
    }

    @Test
    void acceptWithoutRequests() {
        sam.performCommand("trade accept");
        assertEquals("MR-Trades » You have no pending trade requests.", only(sam));
        sam.performCommand("trade accept Kim");
        assertEquals("MR-Trades » You have no trade request from Kim.", only(sam));
    }

    @Test
    void mutualRequestAccepts() {
        alex.performCommand("trade Sam");
        drain(alex);
        drain(sam);

        sam.performCommand("trade Alex");

        assertEquals(0, pending());
        assertEquals(List.of(), drain(alex), "no second request was sent");
    }

    @Test
    void denyTellsBoth() {
        alex.performCommand("trade Sam");
        drain(alex);
        drain(sam);

        sam.performCommand("trade deny Alex");

        assertEquals("MR-Trades » You denied Alex's trade request.", only(sam));
        assertEquals("MR-Trades » Sam denied your trade request.", only(alex));
        assertEquals(0, pending());
    }

    @Test
    void cancelWithdrawsOutgoingRequests() {
        alex.performCommand("trade Sam");
        drain(alex);

        alex.performCommand("trade cancel");

        assertEquals("MR-Trades » You withdrew your trade request.", only(alex));
        assertEquals(0, pending());
        alex.performCommand("trade cancel");
        assertEquals("MR-Trades » You have no pending trade requests.", only(alex));
    }

    @Test
    void duplicateRequest() {
        alex.performCommand("trade Sam");
        drain(alex);
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » You already sent Sam a request.", only(alex));
    }

    @Test
    void cooldownAppliesToTheNextRequest() {
        PlayerMock kim = server.addPlayer("Kim");
        kim.teleport(new Location(world, 1, 64, 1));
        alex.performCommand("trade Sam");
        drain(alex);

        alex.performCommand("trade Kim");

        assertEquals("MR-Trades » Wait 5s before sending another request.", only(alex));
        assertTrue(plugin.requests().requests().find(alex.getUniqueId(), kim.getUniqueId()).isEmpty());
    }

    @Test
    void cooldownBypass() {
        PlayerMock kim = server.addPlayer("Kim");
        kim.teleport(new Location(world, 1, 64, 1));
        alex.addAttachment(plugin, "mrtrades.bypass.cooldown", true);
        alex.performCommand("trade Sam");
        alex.performCommand("trade Kim");
        assertEquals(2, pending());
    }

    @Test
    void offlineHiddenAndSelf() {
        alex.performCommand("trade Nobody");
        assertEquals("MR-Trades » Nobody isn't online.", only(alex));

        alex.hidePlayer(plugin, sam);
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » Sam isn't online.", only(alex), "a vanished player looks offline");
        alex.showPlayer(plugin, sam);

        alex.performCommand("trade Alex");
        assertEquals("MR-Trades » You can't trade with yourself.", only(alex));
        assertEquals(0, pending());
    }

    @Test
    void toggleBlocksRequestsAndPersistsOnThePlayer() {
        sam.performCommand("trade toggle");
        assertEquals("MR-Trades » You no longer receive trade requests.", only(sam));

        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » Sam isn't accepting trade requests.", only(alex));
        assertEquals(0, pending());

        alex.addAttachment(plugin, "mrtrades.bypass.toggle", true);
        alex.performCommand("trade Sam");
        assertEquals(1, pending(), "bypass.toggle sends anyway");

        drain(sam);
        sam.performCommand("trade toggle");
        assertEquals("MR-Trades » You receive trade requests again.", only(sam));
    }

    @Test
    void creativeIsBlocked() {
        sam.setGameMode(GameMode.CREATIVE);
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » Sam can't trade in their game mode.", only(alex));

        alex.setGameMode(GameMode.CREATIVE);
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » You can't trade in creative mode.", only(alex));
    }

    @Test
    void tooFarAndOtherWorld() {
        sam.teleport(new Location(world, 20, 64, 0));
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » You need to be within 16 blocks of Sam.", only(alex));

        World nether = server.addSimpleWorld("world_nether");
        sam.teleport(new Location(nether, 0, 64, 0));
        alex.performCommand("trade Sam");
        assertEquals("MR-Trades » Sam is in another world.", only(alex));
    }

    @Test
    void acceptIsCheckedAgainAndKeepsTheRequest() {
        alex.performCommand("trade Sam");
        drain(sam);
        alex.teleport(new Location(world, 40, 64, 0));

        sam.performCommand("trade accept");

        assertEquals("MR-Trades » You need to be within 16 blocks of Alex.", only(sam));
        assertEquals(1, pending(), "they can walk closer and accept again");
        alex.teleport(new Location(world, 1, 64, 0));
        sam.performCommand("trade accept");
        assertEquals(0, pending());
    }

    @Test
    void requestExpiresAndBothAreTold() throws Exception {
        java.nio.file.Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        java.nio.file.Files.writeString(config, java.nio.file.Files.readString(config)
                .replace("timeout-seconds: 30", "timeout-seconds: 5"));
        plugin.reload(server.getConsoleSender());
        alex.performCommand("trade Sam");
        drain(alex);
        drain(sam);

        Thread.sleep(5100);
        server.getScheduler().performTicks(21);

        assertEquals("MR-Trades » Your trade request to Sam expired.", only(alex));
        assertEquals("MR-Trades » The trade request from Alex expired.", only(sam));
        assertEquals(0, pending());
    }

    @Test
    void quittingDropsRequestsBothWays() {
        alex.performCommand("trade Sam");
        sam.disconnect();
        assertEquals(0, pending());
    }

    @Test
    void permissionsAreChecked() {
        PlayerMock kim = server.addPlayer("Kim");
        kim.addAttachment(plugin, "mrtrades.trade", false);
        kim.addAttachment(plugin, "mrtrades.toggle", false);

        kim.performCommand("trade Sam");
        assertEquals("MR-Trades » You don't have permission to do that.", only(kim));
        kim.performCommand("trade toggle");
        assertEquals("MR-Trades » You don't have permission to do that.", only(kim));
    }

    @Test
    void consoleIsRefused() {
        server.dispatchCommand(server.getConsoleSender(), "trade Sam");
        assertEquals(0, pending());
    }

    @Test
    void helpWithoutArguments() {
        alex.performCommand("trade");
        assertEquals(6, drain(alex).size());
    }

    @Test
    void tabCompletion() {
        PlayerMock hidden = server.addPlayer("Hidden");
        alex.hidePlayer(plugin, hidden);

        List<String> first = server.getCommandTabComplete(alex, "trade ");
        assertTrue(first.containsAll(List.of("accept", "deny", "cancel", "toggle", "Sam")), first.toString());
        assertFalse(first.contains("Alex"), "not yourself");
        assertFalse(first.contains("Hidden"), "not hidden players");
        assertEquals(List.of("Sam"), server.getCommandTabComplete(alex, "trade s"));

        sam.performCommand("trade Alex");
        assertEquals(List.of("Sam"), server.getCommandTabComplete(alex, "trade accept "));
    }
}
