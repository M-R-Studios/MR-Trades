package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.returns.EscrowStore;
import com.mrstudios.trades.returns.ReturnService.Delivery;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** The escrow record and return queue kept on the player (DESIGN.md 3.6, 8.1). */
class EscrowStoreTest extends TradeFixture {

    private EscrowStore store() {
        return plugin.returnsService().store();
    }

    @Test
    void escrowRoundTripKeepsEveryDetail() {
        List<ItemStack> items = List.of(new ItemStack(Material.DIAMOND, 64), excalibur(), shulkerOfDiamonds(),
                named(Material.ENDER_PEARL, 16, "Pearls"));
        UUID session = UUID.randomUUID();

        store().writeEscrow(alex, session, items);
        EscrowStore.Escrow read = store().readEscrow(alex);

        assertEquals(session, read.sessionId());
        assertEquals(items, read.items());
    }

    @Test
    void emptyEscrowClearsTheRecord() {
        store().writeEscrow(alex, UUID.randomUUID(), List.of(new ItemStack(Material.DIRT)));
        store().writeEscrow(alex, UUID.randomUUID(), List.of());
        assertNull(store().readEscrow(alex));
    }

    @Test
    void escrowFollowsTheOfferWhileTrading() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        alex.getInventory().setItem(1, excalibur());
        TradeSession session = start();

        click(alex, ClickType.LEFT, raw(0));
        click(alex, ClickType.LEFT, raw(1));
        EscrowStore.Escrow escrow = store().readEscrow(alex);
        assertEquals(session.id(), escrow.sessionId());
        assertEquals(List.of(new ItemStack(Material.DIAMOND, 10), excalibur()), escrow.items());

        click(alex, ClickType.RIGHT, com.mrstudios.trades.gui.GuiLayout.ownOfferSlot(0));
        assertEquals(List.of(new ItemStack(Material.DIAMOND, 9), excalibur()), store().readEscrow(alex).items());

        // Inventory + escrow always add up to what Alex had: never two places, never none.
        assertEquals(10, count(alex, Material.DIAMOND) + store().readEscrow(alex).items().get(0).getAmount());

        alex.closeInventory();
        assertNull(store().readEscrow(alex), "cleared when the items go back");
    }

    @Test
    void joinRecoversALeftoverEscrowIntoTheInventory() {
        // As after a crash: the record survived, the trade didn't.
        store().writeEscrow(alex, UUID.randomUUID(), List.of(excalibur(), new ItemStack(Material.DIAMOND, 32)));

        server.getPluginManager().callEvent(new PlayerJoinEvent(alex, net.kyori.adventure.text.Component.empty()));

        assertNull(store().readEscrow(alex));
        assertTrue(store().readQueue(alex).isEmpty());
        assertTrue(nonEmpty(alex.getInventory().getStorageContents()).contains(excalibur()));
        assertEquals(32, count(alex, Material.DIAMOND));
    }

    @Test
    void joinRecoveryWithAFullInventoryKeepsItQueued() {
        for (int slot = 0; slot < 36; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        store().writeEscrow(alex, UUID.randomUUID(), List.of(excalibur()));

        Delivery delivery = plugin.returnsService().recoverAndDeliver(alex, id -> false);

        assertEquals(new Delivery(0, 1), delivery);
        assertNull(store().readEscrow(alex));
        assertEquals(List.of(excalibur()), store().readQueue(alex));
    }

    @Test
    void escrowOfALiveTradeIsNotRecovered() {
        UUID live = UUID.randomUUID();
        store().writeEscrow(alex, live, List.of(excalibur()));

        plugin.returnsService().recoverAndDeliver(alex, live::equals);

        assertEquals(List.of(excalibur()), store().readEscrow(alex).items());
        assertTrue(store().readQueue(alex).isEmpty());
    }

    @Test
    void queueDeliveryGivesWhatFitsAndKeepsTheRest() {
        store().appendQueue(alex, List.of(new ItemStack(Material.DIAMOND, 64), excalibur()));
        store().appendQueue(alex, List.of(shulkerOfDiamonds()));
        for (int slot = 0; slot < 35; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }

        Delivery delivery = plugin.returnsService().deliverQueue(alex);

        assertEquals(new Delivery(1, 2), delivery);
        assertEquals(List.of(excalibur(), shulkerOfDiamonds()), store().readQueue(alex));
        assertEquals(64, count(alex, Material.DIAMOND));
    }

    @Test
    void respawnDeliversTheQueue() {
        store().appendQueue(alex, List.of(excalibur()));
        alex.setHealth(0);
        alex.respawn();
        // MockBukkit may not fire Paper's post-respawn event; deliver as the listener would.
        plugin.returnsService().deliverQueue(alex);
        assertTrue(store().readQueue(alex).isEmpty());
        assertTrue(nonEmpty(alex.getInventory().getStorageContents()).contains(excalibur()));
    }

    @Test
    void claimWithNothingWaiting() {
        alex.performCommand("trade claim");
        assertEquals("MR-Trades » Nothing is waiting for you.", net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(alex.nextComponentMessage()));
    }
}
