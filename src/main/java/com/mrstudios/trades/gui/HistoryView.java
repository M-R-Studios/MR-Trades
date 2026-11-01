package com.mrstudios.trades.gui;

import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.history.HistoryQuery;
import com.mrstudios.trades.history.TradeRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Draws the history windows (DESIGN.md 6.5). Everything in them is a marked display copy, and
 * every click is cancelled, so nothing can be taken out.
 */
public final class HistoryView {

    /** Longest item summary shown in an entry's lore; the full text stays in the database. */
    static final int SUMMARY_MAX = 100;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Supplier<Messages> messages;
    private final DisplayItems display;
    private final Function<BigDecimal, String> money;

    /** @param money formats an amount like the trade window does */
    public HistoryView(Supplier<Messages> messages, DisplayItems display, Function<BigDecimal, String> money) {
        this.messages = messages;
        this.display = display;
        this.money = money;
    }

    /** A page of the list. */
    public Inventory list(HistoryQuery.Result page) {
        Messages m = messages.get();
        HistoryHolder holder = new HistoryHolder(page, null);
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.HISTORY_SIZE, m.component("gui.history-title",
                player(page.query().name()), Placeholder.unparsed("page", String.valueOf(page.query().page()))));
        holder.setInventory(inventory);
        List<TradeRecord> rows = page.rows();
        for (int i = 0; i < rows.size() && i < HistoryQuery.PAGE_SIZE; i++) {
            inventory.setItem(i, entry(m, rows.get(i), page.query().player()));
        }
        ItemStack filler = filler();
        for (int slot = HistoryQuery.PAGE_SIZE; slot < GuiLayout.HISTORY_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        if (page.hasPrevious()) {
            inventory.setItem(GuiLayout.HISTORY_PREVIOUS, button(Material.ARROW, m.component("gui.history-prev")));
        }
        inventory.setItem(GuiLayout.HISTORY_CLOSE, button(Material.BARRIER, m.component("gui.history-back")));
        if (page.hasNext()) {
            inventory.setItem(GuiLayout.HISTORY_NEXT, button(Material.ARROW, m.component("gui.history-next")));
        }
        return inventory;
    }

    /** One trade in detail, from the viewed player's side. */
    public Inventory detail(HistoryQuery.Result page, TradeRecord record) {
        Messages m = messages.get();
        TradeRecord.View view = record.viewOf(page.query().player());
        HistoryHolder holder = new HistoryHolder(page, record);
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.HISTORY_SIZE,
                m.component("gui.history-detail-title", player(view.partnerName())));
        holder.setInventory(inventory);
        ItemStack filler = filler();
        ItemStack divider = display.button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < GuiLayout.HISTORY_SIZE; slot++) {
            int column = slot % 9;
            boolean itemArea = slot < 36 && column != 4;
            inventory.setItem(slot, itemArea ? null : column == 4 && slot < 45 ? divider : filler);
        }
        Component preview = m.component("gui.display-lore");
        showItems(inventory, m, view.gaveItems(), true, preview);
        showItems(inventory, m, view.gotItems(), false, preview);
        inventory.setItem(GuiLayout.DETAIL_GAVE_INFO, info(m, "gui.history-gave", view.gaveMoney(), view.gaveXp()));
        inventory.setItem(GuiLayout.DETAIL_GOT_INFO, info(m, "gui.history-got", view.gotMoney(), view.gotXp()));
        inventory.setItem(GuiLayout.DETAIL_BACK, button(Material.ARROW, m.component("gui.history-back")));
        return inventory;
    }

    private void showItems(Inventory inventory, Messages m, byte[] bytes, boolean gave, Component preview) {
        List<ItemStack> items;
        try {
            items = TradeRecord.deserialize(bytes);
        } catch (RuntimeException e) {
            // E.g. stored by a newer Minecraft version: show that, not a broken page.
            inventory.setItem(GuiLayout.detailSlot(gave, 0), button(Material.BARRIER, m.component("gui.history-unreadable")));
            return;
        }
        for (int i = 0; i < items.size() && i < GuiLayout.OFFER_SLOTS; i++) {
            ItemStack item = items.get(i);
            if (item != null && !item.getType().isAir()) {
                inventory.setItem(GuiLayout.detailSlot(gave, i), display.copyOf(item, preview));
            }
        }
    }

    private ItemStack info(Messages m, String key, BigDecimal amount, int xp) {
        List<Component> lore = new ArrayList<>();
        if (amount.signum() != 0) {
            lore.add(m.component("gui.money", Placeholder.unparsed("amount", money.apply(amount))));
        }
        if (xp != 0) {
            lore.add(m.component("gui.xp", Placeholder.unparsed("amount", String.valueOf(xp))));
        }
        return display.button(Material.PAPER, m.component(key), lore);
    }

    private ItemStack entry(Messages m, TradeRecord record, UUID viewed) {
        TradeRecord.View view = record.viewOf(viewed);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try {
            // No remote lookup: the client shows the skin it knows, or a default one.
            meta.setPlayerProfile(Bukkit.createProfile(view.partnerId(), view.partnerName()));
        } catch (RuntimeException ignored) {
            // A plain head is fine.
        }
        TagResolver[] placeholders = {
            player(view.partnerName()),
            Placeholder.unparsed("date", DATE.format(Instant.ofEpochMilli(record.completedAt()))),
            Placeholder.component("gave", side(m, view.gaveSummary(), view.gaveMoney(), view.gaveXp())),
            Placeholder.component("got", side(m, view.gotSummary(), view.gotMoney(), view.gotXp()))
        };
        meta.displayName(DisplayItems.plain(m.component("gui.history-entry", placeholders)));
        List<Component> lore = new ArrayList<>(m.components("gui.history-entry-lore", placeholders));
        if (record.economyError()) {
            lore.add(m.component("gui.history-economy-error"));
        }
        meta.lore(lore.stream().map(DisplayItems::plain).toList());
        head.setItemMeta(meta);
        return display.marked(head);
    }

    /** One side for the lore: items, money and levels, or "nothing". */
    private Component side(Messages m, String summary, BigDecimal amount, int xp) {
        List<Component> parts = new ArrayList<>();
        if (!summary.isEmpty()) {
            parts.add(Component.text(summary.length() > SUMMARY_MAX ? summary.substring(0, SUMMARY_MAX - 1) + "…" : summary));
        }
        if (amount.signum() != 0) {
            parts.add(Component.text(money.apply(amount)));
        }
        if (xp != 0) {
            parts.add(m.component("gui.history-xp", Placeholder.unparsed("amount", String.valueOf(xp))));
        }
        if (parts.isEmpty()) {
            return m.component("gui.history-nothing");
        }
        return Component.join(JoinConfiguration.separator(Component.text(", ")), parts);
    }

    private ItemStack filler() {
        return display.button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private ItemStack button(Material material, Component name) {
        return display.button(material, name, List.of());
    }

    private static TagResolver player(String name) {
        return Placeholder.unparsed("player", name);
    }
}
