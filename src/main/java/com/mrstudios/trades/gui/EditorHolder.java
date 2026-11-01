package com.mrstudios.trades.gui;

import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks a money or XP editor (DESIGN.md 3.3.1). The +/- buttons change a draft held here; only
 * "Done" puts it into the offer, through the session, so the editor itself never changes a trade.
 */
public final class EditorHolder implements InventoryHolder {

    /** What the editor edits. Money arrives in step 6. */
    public enum Kind {
        XP
    }

    private final TradeSession session;
    private final Side side;
    private final Kind kind;
    private long draft;
    private Inventory inventory;

    public EditorHolder(TradeSession session, Side side, Kind kind, long draft) {
        this.session = session;
        this.side = side;
        this.kind = kind;
        this.draft = draft;
    }

    /**
     * The editor behind {@code inventory}, or null. Callers only ask for players who are trading,
     * so ordinary 27-slot chests never pay for {@code getHolder()}'s block-state snapshot.
     */
    public static EditorHolder of(Inventory inventory) {
        if (inventory == null || inventory.getSize() != GuiLayout.EDITOR_SIZE) {
            return null;
        }
        return inventory.getHolder() instanceof EditorHolder editor ? editor : null;
    }

    public TradeSession session() {
        return session;
    }

    public Side side() {
        return side;
    }

    public Kind kind() {
        return kind;
    }

    public long draft() {
        return draft;
    }

    public void setDraft(long draft) {
        this.draft = draft;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
