package com.mrstudios.trades.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.MRTrades;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Sneak-right-click requests (DESIGN.md step 8, M22). */
class RequestListenerTest {

    private ServerMock server;
    private MRTrades plugin;
    private World world;
    private PlayerMock alex;
    private PlayerMock sam;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRTrades.class);
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        sam = server.addPlayer("Sam");
        alex.teleport(new Location(world, 0, 64, 0));
        sam.teleport(new Location(world, 2, 64, 0));
        // Cooldown off so each test can send as often as it needs.
        setConfig("cooldown-seconds: 5", "cooldown-seconds: 0");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void setConfig(String from, String to) throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace(from, to));
        plugin.reload(server.getConsoleSender());
    }

    private PlayerInteractEntityEvent sneakClick(PlayerMock who, org.bukkit.entity.Entity target, EquipmentSlot hand) {
        who.setSneaking(true);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(who, target, hand);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private boolean requested(Player from, Player to) {
        return plugin.requests().requests().find(from.getUniqueId(), to.getUniqueId()).isPresent();
    }

    @Test
    void sneakRightClickSendsARequest() {
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertTrue(requested(alex, sam));
    }

    @Test
    void sneakRightClickingTheRequesterAccepts() {
        sneakClick(alex, sam, EquipmentSlot.HAND);
        sneakClick(sam, alex, EquipmentSlot.HAND);
        assertTrue(plugin.trades().registry().isTrading(alex.getUniqueId()), "the trade started");
        assertSame(plugin.trades().registry().get(alex.getUniqueId()), plugin.trades().registry().get(sam.getUniqueId()));
    }

    @Test
    void withoutSneakingNothingHappens() {
        alex.setSneaking(false);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(alex, sam, EquipmentSlot.HAND));
        assertFalse(requested(alex, sam));
    }

    @Test
    void offHandIsIgnored() {
        sneakClick(alex, sam, EquipmentSlot.OFF_HAND);
        assertFalse(requested(alex, sam), "the event fires once per hand; only the main hand acts");
    }

    @Test
    void canBeTurnedOff() throws Exception {
        setConfig("sneak-right-click: true", "sneak-right-click: false");
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertFalse(requested(alex, sam));
    }

    @Test
    void needsTheSneakPermission() {
        alex.addAttachment(plugin, "mrtrades.trade.sneak", false);
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertFalse(requested(alex, sam));
        alex.performCommand("trade Sam");
        assertTrue(requested(alex, sam), "the command still works");
    }

    @Test
    void needsTheTradePermission() {
        alex.addAttachment(plugin, "mrtrades.trade", false);
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertFalse(requested(alex, sam));
    }

    @Test
    void npcWithCitizensMetadataIsIgnored() {
        sam.setMetadata("NPC", new FixedMetadataValue(plugin, true));
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertFalse(requested(alex, sam));
        assertNull(RequestListener.realPlayer(sam));
    }

    @Test
    void playerEntityThatIsNotTheOnlinePlayerIsIgnored() {
        // What NPC plugins spawn: a Player entity that isn't the online player with its UUID.
        PlayerMock npc = new PlayerMock(server, "Shopkeeper");
        npc.teleport(new Location(world, 1, 64, 0));
        sneakClick(alex, npc, EquipmentSlot.HAND);
        assertNull(RequestListener.realPlayer(npc));
        assertEquals(0, plugin.requests().requests().size());
    }

    @Test
    void nonPlayerEntitiesAreIgnored() {
        org.bukkit.entity.Entity cow = world.spawnEntity(new Location(world, 1, 64, 1), org.bukkit.entity.EntityType.COW);
        sneakClick(alex, cow, EquipmentSlot.HAND);
        assertEquals(0, plugin.requests().requests().size());
    }

    @Test
    void playersNamedLikeASubcommandCanBeRequestedBySneakClick() {
        PlayerMock accept = server.addPlayer("accept");
        accept.teleport(new Location(world, 1, 64, 1));
        sneakClick(alex, accept, EquipmentSlot.HAND);
        assertTrue(requested(alex, accept), "design 2.1: reserved names are reachable this way");
    }

    @Test
    void theUsualChecksStillApply() {
        sam.teleport(new Location(world, 40, 64, 0));
        sneakClick(alex, sam, EquipmentSlot.HAND);
        assertFalse(requested(alex, sam), "too far: refused like /trade");
    }

    @Test
    void cancelledInteractionsAreRespected() {
        alex.setSneaking(true);
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(alex, sam, EquipmentSlot.HAND);
        event.setCancelled(true); // e.g. a protection plugin denied it
        server.getPluginManager().callEvent(event);
        assertFalse(requested(alex, sam));
    }

    @Test
    void holdingRightClickSendsOneRequestPerSecond() {
        long[] now = {1_000};
        RequestListener listener = new RequestListener(plugin, plugin.requests(), () -> now[0]);
        alex.setSneaking(true);
        listener.onSneakClick(new PlayerInteractEntityEvent(alex, sam, EquipmentSlot.HAND));
        plugin.requests().requests().removeAllOf(alex.getUniqueId());

        now[0] += RequestListener.REPEAT_MS - 1;
        listener.onSneakClick(new PlayerInteractEntityEvent(alex, sam, EquipmentSlot.HAND));
        assertFalse(requested(alex, sam), "a held right-click repeats; it doesn't spam requests");

        now[0] += 1;
        listener.onSneakClick(new PlayerInteractEntityEvent(alex, sam, EquipmentSlot.HAND));
        assertTrue(requested(alex, sam));
    }

    @Test
    void whileTradingSneakClicksDoNothing() {
        sneakClick(alex, sam, EquipmentSlot.HAND);
        sneakClick(sam, alex, EquipmentSlot.HAND);
        PlayerMock kim = server.addPlayer("Kim");
        kim.teleport(new Location(world, 1, 64, 2));

        PlayerInteractEntityEvent event = sneakClick(alex, kim, EquipmentSlot.HAND);

        assertTrue(event.isCancelled(), "interactions are cancelled while trading");
        assertFalse(requested(alex, kim));
    }
}
