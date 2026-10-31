package com.mrstudios.trades.gui;

import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import com.mrstudios.trades.trade.TradeState;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Builds and redraws the trade window (DESIGN.md 3.3) from the session. Everything in it is a
 * marked copy ({@link DisplayItems}); redrawing only replaces copies, so it can run at any time.
 */
public final class TradeView {

    private final Supplier<Messages> messages;
    private final DisplayItems display;
    private final Supplier<Integer> countdownSeconds;

    public TradeView(Supplier<Messages> messages, DisplayItems display, Supplier<Integer> countdownSeconds) {
        this.messages = messages;
        this.display = display;
        this.countdownSeconds = countdownSeconds;
    }

    /** A new window for {@code side}, already drawn. */
    public TradeHolder create(TradeSession session, Side side) {
        TradeHolder holder = new TradeHolder(session, side);
        Component title = messages.get().component("gui.trade-title", player(session.other(side).name()));
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.TRADE_SIZE, title);
        holder.setInventory(inventory);
        render(holder);
        return holder;
    }

    /** Redraws every slot of {@code holder}'s window. */
    public void render(TradeHolder holder) {
        Messages m = messages.get();
        TradeSession session = holder.session();
        Side own = holder.side();
        Side other = session.other(own);
        ItemStack[] contents = new ItemStack[GuiLayout.TRADE_SIZE];

        ItemStack filler = display.button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        ItemStack divider = display.button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < GuiLayout.TRADE_SIZE; slot++) {
            contents[slot] = switch (GuiLayout.tradeSlot(slot)) {
                case DIVIDER -> divider;
                case OWN_OFFER, OTHER_OFFER -> null;
                default -> filler;
            };
        }

        contents[GuiLayout.OWN_HEAD] = head(own, m.component("gui.head-you", player(own.name())));
        contents[GuiLayout.OTHER_HEAD] = head(other, m.component("gui.head-other", player(other.name())));
        contents[GuiLayout.STATUS] = status(session, m);

        Component preview = m.component("gui.display-lore");
        List<ItemStack> ownItems = own.offer().items();
        for (int i = 0; i < ownItems.size(); i++) {
            contents[GuiLayout.ownOfferSlot(i)] = display.copyOf(ownItems.get(i), preview);
        }
        List<ItemStack> otherItems = other.offer().items();
        for (int i = 0; i < otherItems.size(); i++) {
            contents[GuiLayout.otherOfferSlot(i)] = display.copyOf(otherItems.get(i), preview);
        }

        // Money (step 6) and XP (step 5) buttons stay hidden until those steps.
        boolean confirmed = own.confirmedAtVersion() == session.version();
        if (confirmed) {
            contents[GuiLayout.CONFIRM] = display.button(Material.LIME_DYE, m.component("gui.confirmed"), List.of());
        } else if (session.isLocked(own)) {
            contents[GuiLayout.CONFIRM] = display.button(Material.GRAY_DYE, m.component("gui.confirm-locked"), List.of());
        } else {
            contents[GuiLayout.CONFIRM] = display.button(Material.RED_DYE, m.component("gui.confirm"), List.of());
        }
        contents[GuiLayout.CANCEL] = display.button(Material.BARRIER, m.component("gui.cancel"), List.of());
        boolean otherConfirmed = other.confirmedAtVersion() == session.version();
        contents[GuiLayout.OTHER_CONFIRM] = otherConfirmed
                ? display.button(Material.LIME_DYE, m.component("gui.other-confirmed", player(other.name())), List.of())
                : display.button(Material.RED_DYE, m.component("gui.other-not-confirmed", player(other.name())), List.of());

        holder.getInventory().setContents(contents);
    }

    private ItemStack status(TradeSession session, Messages m) {
        if (session.state() == TradeState.COUNTDOWN) {
            // The countdown itself arrives in step 5; until then the status shows where it will run.
            int seconds = countdownSeconds.get();
            ItemStack clock = display.button(Material.CLOCK,
                    m.component("gui.status-countdown", Placeholder.unparsed("seconds", String.valueOf(seconds))),
                    List.of());
            clock.setAmount(Math.max(1, Math.min(64, seconds)));
            return clock;
        }
        boolean aDone = session.a().confirmedAtVersion() == session.version();
        boolean bDone = session.b().confirmedAtVersion() == session.version();
        Component text;
        if (aDone != bDone) {
            text = m.component("gui.status-waiting", player((aDone ? session.b() : session.a()).name()));
        } else {
            text = m.component("gui.status-waiting-both");
        }
        return display.button(Material.CLOCK, text, List.of());
    }

    private ItemStack head(Side side, Component name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        Player online = Bukkit.getPlayer(side.playerId());
        if (online != null) {
            meta.setPlayerProfile(online.getPlayerProfile());
        }
        meta.displayName(DisplayItems.plain(name));
        head.setItemMeta(meta);
        return display.marked(head);
    }

    private static TagResolver player(String name) {
        return Placeholder.unparsed("player", name);
    }
}
