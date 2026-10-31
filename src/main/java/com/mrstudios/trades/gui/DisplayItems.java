package com.mrstudios.trades.gui;

import com.mrstudios.trades.MRTrades;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Items shown in our windows. Every one is a copy marked {@code mrtrades:display}, so if one ever
 * leaves a window (a bug or an exploit) it can be recognised and removed (DESIGN.md 3.5 #24). The
 * real offered items never appear in any inventory while they're in a trade.
 */
public final class DisplayItems {

    private final NamespacedKey key;

    public DisplayItems() {
        this.key = new NamespacedKey(MRTrades.NAMESPACE, "display");
    }

    public boolean isDisplay(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** A marked copy of a real item, with {@code previewLore} added under its own lore. */
    public ItemStack copyOf(ItemStack real, Component previewLore) {
        ItemStack copy = real.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(plain(previewLore));
        meta.lore(lore);
        mark(meta);
        copy.setItemMeta(meta);
        return copy;
    }

    /** A marked button or decoration. */
    public ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(DisplayItems::plain).toList());
        }
        mark(meta);
        item.setItemMeta(meta);
        return item;
    }

    /** Marks an item that was built elsewhere (e.g. a player head). */
    public ItemStack marked(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        mark(meta);
        item.setItemMeta(meta);
        return item;
    }

    private void mark(ItemMeta meta) {
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    /** Item names and lore are italic by default; ours aren't unless the message says so. */
    public static Component plain(Component text) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
