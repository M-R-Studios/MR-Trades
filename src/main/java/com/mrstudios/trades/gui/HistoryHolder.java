package com.mrstudios.trades.gui;

import com.mrstudios.trades.history.HistoryQuery;
import com.mrstudios.trades.history.TradeRecord;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a history window (DESIGN.md 6.5): a page of trades, or one trade's details when
 * {@link #detail()} is set. The page is kept, so "Back" from the details needs no new query.
 */
public final class HistoryHolder implements InventoryHolder {

    private final HistoryQuery.Result page;
    private final TradeRecord detail;
    private Inventory inventory;

    public HistoryHolder(HistoryQuery.Result page, TradeRecord detail) {
        this.page = page;
        this.detail = detail;
    }

    public HistoryQuery.Result page() {
        return page;
    }

    /** The trade shown in detail, or null on a page of the list. */
    public TradeRecord detail() {
        return detail;
    }

    /** The history window behind {@code inventory}, or null (see {@link TradeHolder#of}). */
    public static HistoryHolder of(Inventory inventory) {
        if (inventory == null || inventory.getSize() != GuiLayout.HISTORY_SIZE) {
            return null;
        }
        return inventory.getHolder() instanceof HistoryHolder history ? history : null;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
