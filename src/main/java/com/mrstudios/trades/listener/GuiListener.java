package com.mrstudios.trades.listener;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.TradeHolder;
import com.mrstudios.trades.rules.ClickAction;
import com.mrstudios.trades.rules.ClickAction.AddFromInventory;
import com.mrstudios.trades.rules.ClickAction.Press;
import com.mrstudios.trades.rules.ClickAction.ReturnFromOffer;
import com.mrstudios.trades.rules.ClickRules;
import com.mrstudios.trades.rules.ClickSnapshot;
import com.mrstudios.trades.trade.EndReason;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeService;
import com.mrstudios.trades.trade.TradeSession;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;

/**
 * Clicks in trade windows (DESIGN.md 3.5 #1-#7). Every click and drag in our window is cancelled,
 * whatever it is; the plugin then performs only what {@link ClickRules} allows. Runs at LOWEST and
 * without {@code ignoreCancelled}, so no other plugin can un-cancel it first.
 */
public final class GuiListener implements Listener {

    private final MRTrades plugin;
    private final TradeService trades;

    public GuiListener(MRTrades plugin, TradeService trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    /** The editor in this view, looked up only for players who are trading (cheap for every other chest click). */
    private EditorHolder editorOf(InventoryView view, Object who) {
        return who instanceof Player player && trades.registry().isTrading(player.getUniqueId())
                ? EditorHolder.of(view.getTopInventory())
                : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        TradeHolder holder = TradeHolder.of(event.getView().getTopInventory());
        if (holder == null) {
            EditorHolder editor = editorOf(event.getView(), event.getWhoClicked());
            if (editor != null) {
                event.setCancelled(true);
                editorClick(event, (Player) event.getWhoClicked(), editor);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        TradeSession session = holder.session();
        Side side = holder.side();
        boolean owner = side.playerId().equals(player.getUniqueId());
        ClickAction action = ClickRules.decide(new ClickSnapshot(ClickSnapshot.View.TRADE, event.getClick(),
                event.getRawSlot(), event.getView().getTopInventory().getSize(), owner, !session.isEnded(),
                trades.moneyShown(side), trades.xpShown(side), 0));
        switch (action) {
            case AddFromInventory add -> trades.addFromInventory(player, session, side, add.playerSlot(), add.wholeStack());
            case ReturnFromOffer back -> trades.returnFromOffer(player, session, side, back.offerIndex(), back.wholeStack());
            case Press press -> press(player, session, side, press);
            default -> {
            }
        }
        // The client predicted the click; send it the real state (needed for Geyser too).
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    private void editorClick(InventoryClickEvent event, Player player, EditorHolder editor) {
        boolean owner = editor.side().playerId().equals(player.getUniqueId());
        ClickAction action = ClickRules.decide(new ClickSnapshot(ClickSnapshot.View.EDITOR, event.getClick(),
                event.getRawSlot(), event.getView().getTopInventory().getSize(), owner, !editor.session().isEnded(),
                false, true, trades.editorButtonCount(editor)));
        if (action instanceof Press press) {
            trades.editorPress(player, editor, press);
        }
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    private void press(Player player, TradeSession session, Side side, Press press) {
        switch (press.button()) {
            case CONFIRM -> trades.toggleConfirm(player, session, side);
            case CANCEL -> trades.abort(session, EndReason.CANCELLED, player.getUniqueId(), Set.of());
            case XP -> trades.openXpEditor(player, session, side);
            case MONEY -> trades.openMoneyEditor(player, session, side);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (TradeHolder.of(event.getView().getTopInventory()) != null
                || editorOf(event.getView(), event.getWhoClicked()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (trades.registry().isTrading(player.getUniqueId()) || trades.display().isDisplay(event.getCursor())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        }
    }
}
