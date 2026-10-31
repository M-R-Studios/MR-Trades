package com.mrstudios.trades.trade;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.gui.GuiLayout;
import com.mrstudios.trades.gui.TradeHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** A mock server with two players standing next to each other, plus item and click helpers. */
abstract class TradeFixture {

    protected ServerMock server;
    protected MRTrades plugin;
    protected World world;
    protected PlayerMock alex;
    protected PlayerMock sam;

    @BeforeEach
    void setUpServer() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRTrades.class);
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        sam = server.addPlayer("Sam");
        alex.teleport(new Location(world, 0, 64, 0));
        sam.teleport(new Location(world, 3, 64, 0));
    }

    @AfterEach
    void tearDownServer() {
        MockBukkit.unmock();
    }

    protected TradeService trades() {
        return plugin.trades();
    }

    protected TradeSession start() {
        return trades().start(alex, sam);
    }

    protected static ItemStack named(Material material, int amount, String name) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    protected static ItemStack excalibur() {
        ItemStack sword = named(Material.DIAMOND_SWORD, 1, "Excalibur");
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        return sword;
    }

    protected static ItemStack shulkerOfDiamonds() {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().addItem(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.EMERALD, 7));
        meta.setBlockState(state);
        box.setItemMeta(meta);
        return box;
    }

    /** Raw slot of a player inventory slot (0-8 hotbar, 9-35 main) below a 54-slot window. */
    protected static int raw(int playerSlot) {
        return playerSlot < 9 ? GuiLayout.TRADE_SIZE + 27 + playerSlot : GuiLayout.TRADE_SIZE + playerSlot - 9;
    }

    /** Fires a click in {@code player}'s open window, as the server would. */
    protected InventoryClickEvent click(PlayerMock player, ClickType type, int rawSlot) {
        return click(player, type, rawSlot, 0);
    }

    protected InventoryClickEvent click(PlayerMock player, ClickType type, int rawSlot, int hotbarKey) {
        SlotType slotType = rawSlot < GuiLayout.TRADE_SIZE ? SlotType.CONTAINER : SlotType.QUICKBAR;
        InventoryClickEvent event = type == ClickType.NUMBER_KEY
                ? new InventoryClickEvent(player.getOpenInventory(), slotType, rawSlot, type,
                        InventoryAction.HOTBAR_SWAP, hotbarKey)
                : new InventoryClickEvent(player.getOpenInventory(), slotType, rawSlot, type, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        return event;
    }

    protected static boolean isTradeWindow(PlayerMock player) {
        return TradeHolder.of(player.getOpenInventory().getTopInventory()) != null;
    }

    /** Total amount of {@code material} in the player's whole inventory and cursor. */
    protected static int count(PlayerMock player, Material material) {
        int total = Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .filter(i -> i.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
        ItemStack cursor = player.getItemOnCursor();
        return total + (cursor != null && cursor.getType() == material ? cursor.getAmount() : 0);
    }

    protected static List<ItemStack> nonEmpty(ItemStack[] items) {
        return Arrays.stream(items).filter(Objects::nonNull).filter(i -> !i.getType().isAir()).toList();
    }

    protected void setConfig(String from, String to) throws Exception {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        Files.writeString(config, Files.readString(config).replace(from, to));
        plugin.reload(server.getConsoleSender());
    }
}
