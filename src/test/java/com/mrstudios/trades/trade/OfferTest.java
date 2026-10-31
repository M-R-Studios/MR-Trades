package com.mrstudios.trades.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** The escrowed offer: room, merging, splitting and the 16-stack limit. Nothing created or lost. */
class OfferTest {

    private Offer offer;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        offer = new Offer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack named(Material material, int amount, String name) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private int total(Material material) {
        return offer.items().stream().filter(i -> i.getType() == material).mapToInt(ItemStack::getAmount).sum();
    }

    @Test
    void emptyOfferHasRoomForSixteenStacks() {
        assertEquals(16 * 64, offer.roomFor(new ItemStack(Material.DIAMOND)));
        assertEquals(16 * 16, offer.roomFor(new ItemStack(Material.ENDER_PEARL)));
        assertEquals(16, offer.roomFor(new ItemStack(Material.DIAMOND_SWORD)));
        assertEquals(0, offer.roomFor(new ItemStack(Material.AIR)));
        assertEquals(0, offer.roomFor(null));
        assertTrue(offer.isEmpty());
    }

    @Test
    void similarItemsMergeIntoExistingStacksFirst() {
        assertTrue(offer.add(new ItemStack(Material.DIAMOND, 40)));
        assertTrue(offer.add(new ItemStack(Material.DIAMOND, 40)));

        assertEquals(2, offer.stackCount());
        assertEquals(64, offer.item(0).getAmount());
        assertEquals(16, offer.item(1).getAmount());
        assertTrue(offer.add(new ItemStack(Material.DIAMOND, 48)));
        assertEquals(2, offer.stackCount(), "the partial stack was topped up, no new stack");
        assertEquals(128, total(Material.DIAMOND));
    }

    @Test
    void namedAndPlainItemsNeverMerge() {
        offer.add(new ItemStack(Material.DIAMOND, 10));
        offer.add(named(Material.DIAMOND, 10, "Shiny"));

        assertEquals(2, offer.stackCount());
        assertEquals("Shiny", offer.item(1).getItemMeta().getDisplayName());
        assertFalse(offer.item(0).hasItemMeta() && offer.item(0).getItemMeta().hasDisplayName());
    }

    @Test
    void sixteenStackLimitAndUnstackables() {
        for (int i = 0; i < 15; i++) {
            assertTrue(offer.add(new ItemStack(Material.DIAMOND_SWORD)));
        }
        assertEquals(1, offer.roomFor(new ItemStack(Material.DIAMOND_SWORD)));
        assertTrue(offer.add(new ItemStack(Material.DIAMOND_SWORD)));

        assertEquals(0, offer.roomFor(new ItemStack(Material.DIAMOND_SWORD)));
        assertEquals(0, offer.roomFor(new ItemStack(Material.DIRT)));
        assertFalse(offer.add(new ItemStack(Material.DIRT)));
        assertEquals(16, offer.stackCount());
    }

    @Test
    void fullOfferStillTakesItemsThatMergeIntoPartialStacks() {
        for (int i = 0; i < 15; i++) {
            offer.add(new ItemStack(Material.STONE, 64));
        }
        offer.add(new ItemStack(Material.ENDER_PEARL, 10));

        assertEquals(6, offer.roomFor(new ItemStack(Material.ENDER_PEARL)));
        assertFalse(offer.add(new ItemStack(Material.ENDER_PEARL, 7)), "too many: rejected as a whole");
        assertEquals(10, total(Material.ENDER_PEARL), "a rejected add changes nothing");
        assertTrue(offer.add(new ItemStack(Material.ENDER_PEARL, 6)));
        assertEquals(16, total(Material.ENDER_PEARL));
    }

    @Test
    void oversizedStackIsSplitAtTheItemLimit() {
        assertTrue(offer.add(new ItemStack(Material.ENDER_PEARL, 40)));

        assertEquals(List.of(16, 16, 8), offer.items().stream().map(ItemStack::getAmount).toList());
    }

    @Test
    void removeOneOrWholeStack() {
        offer.add(new ItemStack(Material.DIAMOND, 10));
        offer.add(new ItemStack(Material.EMERALD, 3));

        ItemStack one = offer.remove(0, 1);
        assertEquals(1, one.getAmount());
        assertEquals(9, offer.item(0).getAmount());

        ItemStack whole = offer.remove(0, 64);
        assertEquals(9, whole.getAmount());
        assertEquals(Material.EMERALD, offer.item(0).getType(), "later stacks move up");
        assertEquals(1, offer.stackCount());

        assertNull(offer.remove(5, 1));
        assertNull(offer.remove(-1, 1));
        assertNull(offer.remove(0, 0));
    }

    @Test
    void addThenRemoveEverythingGivesBackExactlyWhatWentIn() {
        ItemStack sword = named(Material.DIAMOND_SWORD, 1, "Excalibur");
        offer.add(new ItemStack(Material.DIAMOND, 64));
        offer.add(sword.clone());
        offer.add(new ItemStack(Material.ENDER_PEARL, 16));

        List<ItemStack> back = offer.takeAllItems();

        assertEquals(List.of(new ItemStack(Material.DIAMOND, 64), sword, new ItemStack(Material.ENDER_PEARL, 16)), back);
        assertEquals(0, offer.stackCount());
    }

    @Test
    void itemsHandedOutAreCopies() {
        ItemStack moved = new ItemStack(Material.DIAMOND, 10);
        offer.add(moved);
        moved.setAmount(1);
        offer.items().get(0).setAmount(64);
        offer.item(0).setAmount(64);
        offer.itemViews();

        assertEquals(10, offer.item(0).getAmount(), "neither the caller's stack nor the copies reach the offer");
        assertNotSame(offer.item(0), offer.item(0));
    }

    @Test
    void moneyAndXp() {
        assertTrue(offer.setMoney(new BigDecimal("2.50")));
        assertFalse(offer.setMoney(new BigDecimal("2.5")), "same amount, different scale: no change");
        assertFalse(offer.setMoney(new BigDecimal("-1")));
        assertFalse(offer.setMoney(null));
        assertTrue(offer.setXpLevels(3));
        assertFalse(offer.setXpLevels(3));
        assertFalse(offer.setXpLevels(-2));
        assertFalse(offer.isEmpty());
        assertEquals(3, offer.xpLevels());
        assertEquals(new BigDecimal("2.50"), offer.money());
    }
}
