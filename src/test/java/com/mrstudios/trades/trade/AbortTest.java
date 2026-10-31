package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.GuiLayout;
import java.util.List;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Every way a trade can end without completing: everything back to its owner, nobody stuck (DESIGN.md 8.1). */
class AbortTest extends TradeFixture {

    /** Alex offers 64 diamonds, Excalibur and a full shulker; Sam offers 16 pearls. */
    private TradeSession tradeWithItems() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        alex.getInventory().setItem(1, excalibur());
        alex.getInventory().setItem(2, shulkerOfDiamonds());
        sam.getInventory().setItem(4, new ItemStack(Material.ENDER_PEARL, 16));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(alex, ClickType.LEFT, raw(1));
        click(alex, ClickType.LEFT, raw(2));
        click(sam, ClickType.LEFT, raw(4));
        assertEquals(3, session.a().offer().stackCount());
        assertEquals(1, session.b().offer().stackCount());
        assertTrue(nonEmpty(alex.getInventory().getContents()).isEmpty());
        return session;
    }

    /** The exact items are back with their owners, and no trace of the trade is left. */
    private void assertAllBack(TradeSession session) {
        List<ItemStack> alexItems = nonEmpty(alex.getInventory().getStorageContents());
        assertTrue(alexItems.contains(new ItemStack(Material.DIAMOND, 64)), alexItems.toString());
        assertTrue(alexItems.contains(excalibur()), alexItems.toString());
        assertTrue(alexItems.contains(shulkerOfDiamonds()), alexItems.toString());
        assertEquals(3, alexItems.size());
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), nonEmpty(sam.getInventory().getStorageContents()));
        assertNoTrace(session);
    }

    private void assertNoTrace(TradeSession session) {
        assertTrue(session.isEnded());
        assertFalse(session.a().offer().hasItems());
        assertFalse(session.b().offer().hasItems());
        assertTrue(trades().registry().isEmpty());
        assertNull(plugin.returnsService().store().readEscrow(alex));
        server.getScheduler().performTicks(1);
        assertFalse(isTradeWindow(alex), "Alex's window closes the next tick");
        assertFalse(isTradeWindow(sam), "Sam's window closes the next tick");
    }

    @Test
    void cancelButtonReturnsEverything() {
        TradeSession session = tradeWithItems();
        click(sam, ClickType.LEFT, GuiLayout.CANCEL);
        assertAllBack(session);
    }

    @Test
    void escClosingTheWindowReturnsEverything() {
        TradeSession session = tradeWithItems();
        alex.closeInventory();
        assertAllBack(session);
    }

    @Test
    void tradeCancelCommandReturnsEverything() {
        TradeSession session = tradeWithItems();
        alex.performCommand("trade cancel");
        assertAllBack(session);
    }

    @Test
    void adminCancelReturnsEverything() {
        TradeSession session = tradeWithItems();
        server.dispatchCommand(server.getConsoleSender(), "mrtrades cancel Sam");
        assertAllBack(session);
    }

    @Test
    void quitReturnsEverythingBeforeTheyLeave() {
        TradeSession session = tradeWithItems();
        alex.disconnect();
        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size(), "back before the save on quit");
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), nonEmpty(sam.getInventory().getStorageContents()));
        assertTrue(trades().registry().isEmpty());
    }

    @Test
    void deathSendsTheDyingPlayersItemsToTheQueueNotTheDrops() {
        TradeSession session = tradeWithItems();
        alex.setHealth(0);

        assertTrue(session.isEnded());
        assertTrue(nonEmpty(alex.getInventory().getContents()).isEmpty(), "nothing in the inventory to be dropped");
        assertEquals(3, plugin.returnsService().store().readQueue(alex).size());
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), nonEmpty(sam.getInventory().getStorageContents()),
                "the other player gets theirs straight back");
    }

    @Test
    void worldChangeReturnsEverything() {
        TradeSession session = tradeWithItems();
        World nether = server.addSimpleWorld("world_nether");
        alex.teleport(new Location(nether, 0, 64, 0));
        server.getScheduler().performTicks(1);
        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
    }

    @Test
    void walkingTooFarEndsItOnTheNextCheck() {
        TradeSession session = tradeWithItems();
        // setLocation, not teleport: MockBukkit's teleport closes the window, which ends the trade anyway.
        sam.setLocation(new Location(world, 40, 64, 0));
        server.getScheduler().performTicks(11);
        assertAllBack(session);
    }

    @Test
    void movingWithinRangeNeverEndsIt() {
        TradeSession session = tradeWithItems();
        sam.setLocation(new Location(world, 10, 64, 5));
        server.getScheduler().performTicks(40);
        assertFalse(session.isEnded());
    }

    @Test
    void blockedGameModeEndsIt() {
        TradeSession session = tradeWithItems();
        sam.setGameMode(GameMode.CREATIVE);
        assertTrue(session.isEnded());
        assertEquals(List.of(new ItemStack(Material.ENDER_PEARL, 16)), nonEmpty(sam.getInventory().getStorageContents()));
    }

    @Test
    void pluginDisableReturnsEverything() {
        TradeSession session = tradeWithItems();
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
        assertEquals(1, nonEmpty(sam.getInventory().getStorageContents()).size());
        assertFalse(isTradeWindow(alex), "closed at once: no next tick while disabling");
    }

    @Test
    void abortingTwiceReturnsOnce() {
        TradeSession session = tradeWithItems();
        trades().abort(session, EndReason.ADMIN, null, Set.of());
        trades().abort(session, EndReason.ADMIN, null, Set.of());
        alex.closeInventory();
        assertEquals(64, count(alex, Material.DIAMOND));
        assertEquals(16, count(sam, Material.ENDER_PEARL));
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
    }

    @Test
    void fullInventoryOnReturnGoesToTheQueueAndClaimGivesItBack() {
        TradeSession session = tradeWithItems();
        for (int slot = 0; slot < 36; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        alex.getInventory().setItem(7, null);

        click(alex, ClickType.LEFT, GuiLayout.CANCEL);

        assertTrue(session.isEnded());
        assertEquals(2, plugin.returnsService().store().readQueue(alex).size(), "one stack fitted, two queued");
        for (int slot = 0; slot < 36; slot++) {
            if (alex.getInventory().getItem(slot).getType() == Material.STONE) {
                alex.getInventory().setItem(slot, null);
            }
        }
        alex.performCommand("trade claim");
        assertTrue(plugin.returnsService().store().readQueue(alex).isEmpty());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
        assertTrue(nonEmpty(alex.getInventory().getStorageContents()).contains(excalibur()));
    }

    @Test
    void partialClaimKeepsTheRest() {
        tradeWithItems();
        for (int slot = 0; slot < 36; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        click(alex, ClickType.LEFT, GuiLayout.CANCEL);
        assertEquals(3, plugin.returnsService().store().readQueue(alex).size());

        alex.getInventory().setItem(0, null);
        alex.performCommand("trade claim");

        assertEquals(2, plugin.returnsService().store().readQueue(alex).size());
        assertEquals(1, nonEmpty(alex.getInventory().getStorageContents()).stream()
                .filter(i -> i.getType() != Material.STONE).count());
    }

    @Test
    void dropModeDropsLeftoversOwnedByTheOwner() throws Exception {
        setConfig("when-full: QUEUE", "when-full: DROP");
        TradeSession session = tradeWithItems();
        for (int slot = 0; slot < 36; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        click(alex, ClickType.LEFT, GuiLayout.CANCEL);

        assertTrue(session.isEnded());
        List<Item> drops = world.getEntitiesByClass(Item.class).stream().toList();
        assertEquals(3, drops.size());
        // (MockBukkit doesn't implement Item#setOwner; the owner check is in the live tests.)
        assertTrue(plugin.returnsService().store().readQueue(alex).isEmpty());
    }

    @Test
    void bothCanTradeAgainRightAway() {
        TradeSession first = tradeWithItems();
        alex.closeInventory();
        server.getScheduler().performTicks(1);

        TradeSession second = start();

        assertFalse(second.isEnded());
        assertTrue(isTradeWindow(alex));
        assertTrue(isTradeWindow(sam));
        assertTrue(first.isEnded());
        click(alex, ClickType.LEFT, raw(0));
        assertEquals(1, second.a().offer().stackCount(), "the new trade takes items normally");
    }

    @Test
    void watchdogEndsATradeWhoseWindowVanished() {
        TradeSession session = tradeWithItems();
        // Another plugin swaps Sam's view without a close event reaching us.
        sam.openInventory(server.createInventory(null, 9));
        server.getScheduler().performTicks(21);
        assertTrue(session.isEnded());
        assertEquals(3, nonEmpty(alex.getInventory().getStorageContents()).size());
    }
}
