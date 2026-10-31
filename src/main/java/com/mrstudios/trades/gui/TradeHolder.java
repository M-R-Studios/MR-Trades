package com.mrstudios.trades.gui;

import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a trade window: which trade it belongs to and whose view it is. Each trader has their own
 * window, so each sees their own offer on the left.
 */
public final class TradeHolder implements InventoryHolder {

    private final TradeSession session;
    private final Side side;
    private Inventory inventory;

    public TradeHolder(TradeSession session, Side side) {
        this.session = session;
        this.side = side;
    }

    public TradeSession session() {
        return session;
    }

    /** The trader this window belongs to. */
    public Side side() {
        return side;
    }

    /**
     * The trade window behind {@code inventory}, or null. Only 54-slot inventories can be ours, so
     * {@code getHolder()} (which snapshots block states) is skipped for almost every block
     * inventory. ({@code getHolder(false)} would avoid the snapshot, but MockBukkit lacks it.)
     */
    public static TradeHolder of(Inventory inventory) {
        if (inventory == null || inventory.getSize() != GuiLayout.TRADE_SIZE) {
            return null;
        }
        return inventory.getHolder() instanceof TradeHolder trade ? trade : null;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
