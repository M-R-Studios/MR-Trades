package com.mrstudios.trades.trade;

import static com.mrstudios.trades.trade.PlainStack.of;
import static com.mrstudios.trades.trade.PlainStack.pearls;
import static com.mrstudios.trades.trade.PlainStack.sword;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class InventorySimulatorTest {

    private static final int STORAGE = 36;
    private static final int MAX = 64;

    private static List<StackView> empty(int size) {
        return new ArrayList<>(Collections.nCopies(size, null));
    }

    private static List<StackView> full(StackView filler) {
        return new ArrayList<>(Collections.nCopies(STORAGE, filler));
    }

    @Test
    void emptyInventoryTakesUpToItsSize() {
        List<StackView> incoming = new ArrayList<>(Collections.nCopies(STORAGE, of("dirt", 64)));
        assertTrue(InventorySimulator.fits(empty(STORAGE), incoming, MAX));

        incoming.add(of("dirt", 1));
        assertFalse(InventorySimulator.fits(empty(STORAGE), incoming, MAX));
        assertEquals(1, InventorySimulator.leftover(empty(STORAGE), incoming, MAX));
    }

    @Test
    void nothingIncomingAlwaysFits() {
        assertTrue(InventorySimulator.fits(full(of("stone", 64)), List.of(), MAX));
        assertTrue(InventorySimulator.fits(List.of(), List.of(), MAX));
    }

    @Test
    void fullInventoryTakesNothingNew() {
        assertFalse(InventorySimulator.fits(full(of("stone", 64)), List.of(of("dirt", 1)), MAX));
        assertFalse(InventorySimulator.fits(full(of("stone", 64)), List.of(of("stone", 1)), MAX));
    }

    @Test
    void fullInventoryOfPartialStacksTakesTheMatchingRemainder() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(7, of("dirt", 60));
        contents.set(30, of("dirt", 63));

        assertTrue(InventorySimulator.fits(contents, List.of(of("dirt", 5)), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(of("dirt", 6)), MAX));
        assertEquals(1, InventorySimulator.leftover(contents, List.of(of("dirt", 6)), MAX));
    }

    @Test
    void exactlyFullAfterMerging() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, of("dirt", 32));
        contents.set(1, null);

        // 32 to top up slot 0, 64 into the empty slot: exactly full.
        assertTrue(InventorySimulator.fits(contents, List.of(of("dirt", 64), of("dirt", 32)), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(of("dirt", 64), of("dirt", 33)), MAX));
    }

    @Test
    void severalIncomingStacksShareTheSpaceTheyCreate() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(5, null);

        // The first 10 dirt take the empty slot; the next 54 top that new stack up to 64.
        assertTrue(InventorySimulator.fits(contents, List.of(of("dirt", 10), of("dirt", 54)), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(of("dirt", 10), of("dirt", 55)), MAX));
        // A different item can't use the space the dirt left in its stack.
        assertFalse(InventorySimulator.fits(contents, List.of(of("dirt", 10), of("sand", 1)), MAX));
    }

    @Test
    void sixteenStackItemsUseTheirOwnLimit() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, pearls(10));
        contents.set(1, null);

        // 6 more fit on the existing pearls, 16 in the empty slot: 22 in all.
        assertTrue(InventorySimulator.fits(contents, List.of(pearls(16), pearls(6)), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(pearls(16), pearls(7)), MAX));
    }

    @Test
    void oversizedIncomingStackIsSplitAtTheItemLimit() {
        // 64 pearls (more than the 16 limit, e.g. from a command) need four slots.
        List<StackView> contents = full(of("stone", 64));
        for (int i = 0; i < 3; i++) {
            contents.set(i, null);
        }
        assertFalse(InventorySimulator.fits(contents, List.of(pearls(64)), MAX));
        contents.set(3, null);
        assertTrue(InventorySimulator.fits(contents, List.of(pearls(64)), MAX));
    }

    @Test
    void unstackablesNeedOneSlotEach() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, null);
        contents.set(1, null);

        assertTrue(InventorySimulator.fits(contents, List.of(sword("a"), sword("a")), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(sword("a"), sword("a"), sword("a")), MAX));
        // Two identical swords never merge, even though they're similar.
        contents.set(1, sword("a"));
        assertFalse(InventorySimulator.fits(contents, List.of(sword("a"), sword("a")), MAX));
    }

    @Test
    void customMaxStackSizeIsRespected() {
        PlainStack potion = new PlainStack("stackable_potion", 3, 8);
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, new PlainStack("stackable_potion", 6, 8));

        assertTrue(InventorySimulator.fits(contents, List.of(new PlainStack("stackable_potion", 2, 8)), MAX));
        assertFalse(InventorySimulator.fits(contents, List.of(potion), MAX));
    }

    @Test
    void inventoryLimitBelowItemLimitWins() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, null);

        assertTrue(InventorySimulator.fits(contents, List.of(of("dirt", 10)), 10));
        assertFalse(InventorySimulator.fits(contents, List.of(of("dirt", 11)), 10));
    }

    @Test
    void lookAlikesThatAreNotSimilarDoNotMerge() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, of("diamond{name=Shiny}", 1));

        assertFalse(InventorySimulator.fits(contents, List.of(of("diamond", 1)), MAX));
        assertTrue(InventorySimulator.fits(contents, List.of(of("diamond{name=Shiny}", 63)), MAX));
    }

    @Test
    void overstackedExistingSlotIsNotTreatedAsPartial() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(0, pearls(20));

        assertFalse(InventorySimulator.fits(contents, List.of(pearls(1)), MAX));
    }

    @Test
    void zeroAndNullIncomingAreIgnored() {
        List<StackView> incoming = new ArrayList<>();
        incoming.add(null);
        incoming.add(of("dirt", 0));
        assertTrue(InventorySimulator.fits(full(of("stone", 64)), incoming, MAX));
    }

    @Test
    void inputsAreNeverChanged() {
        List<StackView> contents = full(of("stone", 64));
        contents.set(3, null);
        contents.set(4, of("dirt", 10));
        List<StackView> before = new ArrayList<>(contents);
        List<StackView> incoming = List.of(of("dirt", 64), of("dirt", 64));

        InventorySimulator.fits(contents, incoming, MAX);
        InventorySimulator.fits(contents, incoming, MAX);

        assertEquals(before, contents);
        assertEquals(List.of(of("dirt", 64), of("dirt", 64)), incoming);
    }

    @Test
    void inventoryMaxStackMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> InventorySimulator.fits(empty(1), List.of(), 0));
    }

    /** The same rules on real items, and a cross-check against the server's own {@code addItem}. */
    @Nested
    class WithRealItems {

        private ServerMock server;

        @BeforeEach
        void setUp() {
            server = MockBukkit.mock();
        }

        @AfterEach
        void tearDown() {
            MockBukkit.unmock();
        }

        private ItemStack named(Material material, int amount, String name) {
            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(name);
            item.setItemMeta(meta);
            return item;
        }

        private ItemStack shulkerWith(ItemStack content) {
            ItemStack box = new ItemStack(Material.SHULKER_BOX);
            BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
            ShulkerBox state = (ShulkerBox) meta.getBlockState();
            state.getInventory().addItem(content);
            meta.setBlockState(state);
            box.setItemMeta(meta);
            return box;
        }

        private boolean fits(ItemStack[] contents, ItemStack... incoming) {
            return InventorySimulator.fits(ItemStackView.of(contents), ItemStackView.of(incoming), MAX);
        }

        private ItemStack[] fullOf(Material material) {
            ItemStack[] contents = new ItemStack[STORAGE];
            for (int i = 0; i < STORAGE; i++) {
                contents[i] = new ItemStack(material, 64);
            }
            return contents;
        }

        @Test
        void namedAndPlainDiamondsDoNotMerge() {
            ItemStack[] contents = fullOf(Material.STONE);
            contents[0] = named(Material.DIAMOND, 10, "Shiny");

            assertFalse(fits(contents, new ItemStack(Material.DIAMOND, 1)));
            assertTrue(fits(contents, named(Material.DIAMOND, 54, "Shiny")));
            assertFalse(fits(contents, named(Material.DIAMOND, 1, "Other")));
        }

        @Test
        void enchantedAndPlainBooksDoNotMerge() {
            ItemStack[] contents = fullOf(Material.STONE);
            contents[0] = new ItemStack(Material.PAPER, 10);
            ItemStack enchanted = new ItemStack(Material.PAPER, 1);
            enchanted.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);

            assertFalse(fits(contents, enchanted));
            assertTrue(fits(contents, new ItemStack(Material.PAPER, 54)));
        }

        @Test
        void shulkerBoxesNeverStackAndDifferentContentsStayApart() {
            ItemStack[] contents = fullOf(Material.STONE);
            contents[0] = null;

            assertTrue(fits(contents, shulkerWith(new ItemStack(Material.DIAMOND, 64))));
            assertFalse(fits(contents, shulkerWith(new ItemStack(Material.DIAMOND, 64)),
                    shulkerWith(new ItemStack(Material.DIAMOND, 64))));
            assertFalse(new ItemStackView(shulkerWith(new ItemStack(Material.DIAMOND, 1)))
                    .isSimilar(new ItemStackView(shulkerWith(new ItemStack(Material.EMERALD, 1)))));
        }

        @Test
        void enderPearlsAndSwordsUseTheirLimits() {
            ItemStack[] contents = fullOf(Material.STONE);
            contents[0] = new ItemStack(Material.ENDER_PEARL, 12);
            contents[1] = null;

            assertTrue(fits(contents, new ItemStack(Material.ENDER_PEARL, 20)));
            assertFalse(fits(contents, new ItemStack(Material.ENDER_PEARL, 21)));
            assertTrue(fits(contents, new ItemStack(Material.DIAMOND_SWORD)));
            assertFalse(fits(contents, new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.DIAMOND_SWORD)));
        }

        @Test
        void customMaxStackSizeComponentIsRespected() {
            ItemStack[] contents = fullOf(Material.STONE);
            contents[0] = null;
            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD, 1);
            ItemMeta meta = sword.getItemMeta();
            meta.setMaxStackSize(4);
            sword.setItemMeta(meta);
            ItemStack four = sword.clone();
            four.setAmount(4);
            ItemStack five = sword.clone();
            five.setAmount(5);

            assertEquals(4, sword.getMaxStackSize());
            assertTrue(fits(contents, four));
            assertFalse(fits(contents, five));
        }

        /**
         * Paper's {@code CraftInventory#addItem}, ported literally: for each stack, repeatedly top up
         * the first similar partial stack, else fill the first empty slot. An independent model of
         * the real server, used as the oracle below. (MockBukkit's {@code addItem} is not used:
         * it fills the first empty-or-similar slot in slot order, which a real server doesn't.)
         *
         * @return how many items didn't fit
         */
        private int paperAddItem(ItemStack[] slots, ItemStack[] incoming, int inventoryMax) {
            int leftover = 0;
            for (ItemStack original : incoming) {
                ItemStack item = original.clone();
                int limit = Math.min(item.getMaxStackSize(), inventoryMax);
                while (true) {
                    int partial = -1;
                    for (int i = 0; i < slots.length && partial < 0; i++) {
                        if (slots[i] != null && slots[i].getAmount() < limit && slots[i].isSimilar(item)) {
                            partial = i;
                        }
                    }
                    if (partial < 0) {
                        int free = -1;
                        for (int i = 0; i < slots.length && free < 0; i++) {
                            if (slots[i] == null) {
                                free = i;
                            }
                        }
                        if (free < 0) {
                            leftover += item.getAmount();
                            break;
                        }
                        if (item.getAmount() > limit) {
                            ItemStack part = item.clone();
                            part.setAmount(limit);
                            slots[free] = part;
                            item.setAmount(item.getAmount() - limit);
                        } else {
                            slots[free] = item;
                            break;
                        }
                    } else {
                        int room = limit - slots[partial].getAmount();
                        if (item.getAmount() <= room) {
                            slots[partial].setAmount(slots[partial].getAmount() + item.getAmount());
                            break;
                        }
                        slots[partial].setAmount(limit);
                        item.setAmount(item.getAmount() - room);
                    }
                }
            }
            return leftover;
        }

        private ItemStack randomStack(Random random, List<ItemStack> palette, boolean allowOversized) {
            ItemStack item = palette.get(random.nextInt(palette.size())).clone();
            int max = item.getMaxStackSize() * (allowOversized && random.nextInt(10) == 0 ? 4 : 1);
            item.setAmount(1 + random.nextInt(max));
            return item;
        }

        /**
         * Property-style check (fixed seed): on 2000 random inventories with random incoming items
         * (named look-alikes, 16-stacks, unstackables, oversized stacks), the simulator predicts
         * exactly what the server's {@code addItem} leaves over, and never changes its inputs.
         */
        @Test
        void agreesWithPaperAddItemOnRandomInventories() {
            Random random = new Random(20261031L);
            List<ItemStack> palette = List.of(
                    new ItemStack(Material.DIRT),
                    new ItemStack(Material.ENDER_PEARL),
                    new ItemStack(Material.DIAMOND_SWORD),
                    named(Material.DIRT, 1, "Special dirt"),
                    named(Material.DIAMOND_SWORD, 1, "Excalibur"),
                    new ItemStack(Material.SNOWBALL),
                    new ItemStack(Material.DIAMOND),
                    shulkerWith(new ItemStack(Material.DIAMOND, 64)));
            int fullCases = 0;
            for (int round = 0; round < 2000; round++) {
                ItemStack[] contents = new ItemStack[STORAGE];
                int fill = random.nextInt(101);
                for (int slot = 0; slot < STORAGE; slot++) {
                    if (random.nextInt(100) < fill) {
                        contents[slot] = randomStack(random, palette, false);
                    }
                }
                int count = 1 + random.nextInt(16);
                ItemStack[] incoming = new ItemStack[count];
                for (int i = 0; i < count; i++) {
                    incoming[i] = randomStack(random, palette, true);
                }
                ItemStack[] contentsBefore = deepCopy(contents);
                ItemStack[] incomingBefore = deepCopy(incoming);

                int predicted = InventorySimulator.leftover(ItemStackView.of(contents), ItemStackView.of(incoming), MAX);
                int actual = paperAddItem(deepCopy(contents), incoming, MAX);

                assertEquals(actual, predicted, "round " + round + ": " + Arrays.toString(incoming));
                assertTrue(Arrays.equals(contentsBefore, contents), "round " + round + " changed the contents");
                assertTrue(Arrays.equals(incomingBefore, incoming), "round " + round + " changed the incoming items");
                if (actual > 0) {
                    fullCases++;
                }
            }
            // The random cases must include plenty of inventories that run out of room.
            assertTrue(fullCases > 200, "only " + fullCases + " cases ran out of room");
        }

        private ItemStack[] deepCopy(ItemStack[] items) {
            return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
        }

        /** Documents why the oracle above isn't MockBukkit's addItem; if this ever fails, use addItem. */
        @Test
        void mockBukkitAddItemPlacesDifferentlyFromPaper() {
            Inventory inventory = server.createInventory(null, 9);
            inventory.setItem(1, new ItemStack(Material.DIRT, 10));
            inventory.addItem(new ItemStack(Material.DIRT, 5));
            ItemStack[] paper = {null, new ItemStack(Material.DIRT, 10), null, null, null, null, null, null, null};
            paperAddItem(paper, new ItemStack[] {new ItemStack(Material.DIRT, 5)}, MAX);

            assertEquals(15, paper[1].getAmount());
            assertEquals(5, inventory.getItem(0).getAmount(), "MockBukkit now tops up partial stacks first");
        }
    }
}
