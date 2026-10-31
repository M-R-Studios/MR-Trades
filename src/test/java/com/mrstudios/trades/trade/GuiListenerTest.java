package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.trades.gui.GuiLayout;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Items moving between inventory and offer through real click events: nothing created or lost. */
class GuiListenerTest extends TradeFixture {

    @Test
    void startOpensBothWindows() {
        TradeSession session = start();
        assertTrue(isTradeWindow(alex));
        assertTrue(isTradeWindow(sam));
        assertTrue(trades().registry().isTrading(alex.getUniqueId()));
        assertEquals(TradeState.OPEN, session.state());
    }

    @Test
    void everyClickIsCancelled() {
        start();
        for (ClickType type : ClickType.values()) {
            for (int raw : new int[] {0, 4, GuiLayout.ownOfferSlot(0), GuiLayout.otherOfferSlot(0), GuiLayout.CONFIRM,
                raw(0), raw(20)}) {
                if (type == ClickType.LEFT || type == ClickType.SHIFT_LEFT || type == ClickType.RIGHT) {
                    continue;
                }
                assertTrue(click(alex, type, raw).isCancelled(), type + " on " + raw);
            }
        }
        assertTrue(click(alex, ClickType.LEFT, raw(0)).isCancelled());
    }

    @Test
    void leftClickMovesTheWholeStackIntoTheOffer() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 64));
        TradeSession session = start();

        click(alex, ClickType.LEFT, raw(0));

        assertNull(alex.getInventory().getItem(0));
        assertEquals(64, session.a().offer().item(0).getAmount());
        assertEquals(1, session.version());
    }

    @Test
    void rightClickMovesOneItem() {
        alex.getInventory().setItem(12, new ItemStack(Material.DIAMOND, 10));
        TradeSession session = start();

        click(alex, ClickType.RIGHT, raw(12));
        click(alex, ClickType.RIGHT, raw(12));

        assertEquals(8, alex.getInventory().getItem(12).getAmount());
        assertEquals(2, session.a().offer().item(0).getAmount());
    }

    @Test
    void returningItemsBringsThemBack() {
        alex.getInventory().setItem(0, excalibur());
        alex.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 5));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        click(alex, ClickType.LEFT, raw(1));

        click(alex, ClickType.RIGHT, GuiLayout.ownOfferSlot(1));
        assertEquals(4, session.a().offer().item(1).getAmount());
        click(alex, ClickType.LEFT, GuiLayout.ownOfferSlot(0));

        assertEquals(1, session.a().offer().stackCount());
        assertEquals(1, count(alex, Material.DIAMOND));
        assertTrue(nonEmpty(alex.getInventory().getStorageContents()).contains(excalibur()));
    }

    @Test
    void returningIntoAFullInventoryIsRefused() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        for (int slot = 0; slot < 36; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        alex.nextComponentMessage();

        click(alex, ClickType.LEFT, GuiLayout.ownOfferSlot(0));

        assertEquals(5, session.a().offer().item(0).getAmount(), "stays in the offer");
        assertEquals(0, count(alex, Material.DIAMOND));
    }

    @Test
    void clicksOnTheOtherOfferAndDecorationsDoNothing() {
        sam.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        TradeSession session = start();
        click(sam, ClickType.LEFT, raw(0));
        long version = session.version();

        for (int raw : new int[] {GuiLayout.otherOfferSlot(0), 0, 4, 8, 13, 53, GuiLayout.MONEY, GuiLayout.XP,
            GuiLayout.OTHER_CONFIRM}) {
            click(alex, ClickType.LEFT, raw);
            click(alex, ClickType.SHIFT_LEFT, raw);
        }

        assertEquals(version, session.version());
        assertEquals(0, count(alex, Material.DIAMOND));
        assertEquals(5, session.b().offer().item(0).getAmount());
    }

    @Test
    void numberKeyAndOffhandSwapsNeverMoveItems() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        alex.getInventory().setItem(1, new ItemStack(Material.EMERALD, 5));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(1));

        InventoryClickEvent event = click(alex, ClickType.NUMBER_KEY, GuiLayout.ownOfferSlot(0), 0);
        click(alex, ClickType.NUMBER_KEY, GuiLayout.otherOfferSlot(0), 0);
        click(alex, ClickType.SWAP_OFFHAND, GuiLayout.ownOfferSlot(0));
        click(alex, ClickType.DROP, GuiLayout.ownOfferSlot(0));
        click(alex, ClickType.CONTROL_DROP, GuiLayout.ownOfferSlot(0));
        click(alex, ClickType.DOUBLE_CLICK, raw(0));
        click(alex, ClickType.MIDDLE, GuiLayout.ownOfferSlot(0));

        assertTrue(event.isCancelled());
        assertEquals(5, count(alex, Material.DIAMOND));
        assertEquals(5, session.a().offer().item(0).getAmount());
        assertEquals(Material.EMERALD, session.a().offer().item(0).getType());
    }

    @Test
    void theOtherPlayerCantClickInMyWindow() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        sam.getInventory().setItem(0, new ItemStack(Material.EMERALD, 5));
        TradeSession session = start();
        // Sam somehow views Alex's window.
        sam.openInventory(alex.getOpenInventory().getTopInventory());

        click(sam, ClickType.LEFT, raw(0));

        assertEquals(0, session.version());
        assertEquals(5, count(sam, Material.EMERALD));
    }

    @Test
    void displayCopyInTheInventoryIsRemovedNotOffered() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        TradeSession session = start();
        click(alex, ClickType.LEFT, raw(0));
        ItemStack copy = alex.getOpenInventory().getTopInventory().getItem(GuiLayout.ownOfferSlot(0));
        assertTrue(trades().display().isDisplay(copy));
        alex.getInventory().setItem(5, copy);

        click(alex, ClickType.LEFT, raw(5));

        assertNull(alex.getInventory().getItem(5));
        assertEquals(1, session.a().offer().stackCount(), "the copy never becomes a real offer");
        assertEquals(3, session.a().offer().item(0).getAmount());
    }

    @Test
    void offerFullOfStacksRefusesMore() {
        for (int slot = 0; slot < 17; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.DIAMOND_SWORD));
        }
        TradeSession session = start();
        for (int slot = 0; slot < 17; slot++) {
            click(alex, ClickType.LEFT, raw(slot));
        }
        assertEquals(16, session.a().offer().stackCount());
        assertEquals(1, count(alex, Material.DIAMOND_SWORD), "the 17th stays in the inventory");
    }

    @Test
    void partialRoomMovesOnlyWhatFits() {
        for (int slot = 0; slot < 15; slot++) {
            alex.getInventory().setItem(slot, new ItemStack(Material.DIAMOND_SWORD));
        }
        alex.getInventory().setItem(15, new ItemStack(Material.ENDER_PEARL, 10));
        alex.getInventory().setItem(16, new ItemStack(Material.ENDER_PEARL, 16));
        TradeSession session = start();
        for (int slot = 0; slot < 17; slot++) {
            click(alex, ClickType.LEFT, raw(slot));
        }
        assertEquals(16, session.a().offer().stackCount());
        assertEquals(10, count(alex, Material.ENDER_PEARL), "6 topped up the offered pearls, 10 stayed");
        assertEquals(16, session.a().offer().item(15).getAmount());
    }

    @Test
    void anyChangeAfterConfirmingResetsBoth() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        TradeSession session = start();
        click(alex, ClickType.LEFT, GuiLayout.CONFIRM);
        click(sam, ClickType.LEFT, GuiLayout.CONFIRM);
        assertEquals(TradeState.COUNTDOWN, session.state());

        click(alex, ClickType.LEFT, raw(0));

        assertEquals(TradeState.OPEN, session.state());
        assertFalse(session.bothConfirmed());
        assertTrue(session.isLocked(session.b()));
    }

    @Test
    void displayItemsAreMarkedEverywhere() {
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        start();
        click(alex, ClickType.LEFT, raw(0));
        for (ItemStack item : alex.getOpenInventory().getTopInventory().getContents()) {
            if (item != null) {
                assertTrue(trades().display().isDisplay(item), item.toString());
            }
        }
    }

    @Test
    void dragInTheWindowIsCancelled() {
        start();
        org.bukkit.event.inventory.InventoryDragEvent drag = new org.bukkit.event.inventory.InventoryDragEvent(
                alex.getOpenInventory(), null, new ItemStack(Material.DIRT), false,
                java.util.Map.of(raw(0), new ItemStack(Material.DIRT)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
    }
}
