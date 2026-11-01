package com.mrstudios.trades.gui;

import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import com.mrstudios.trades.trade.TradeState;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
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
    private final Supplier<Settings> settings;
    private final Predicate<UUID> canOfferXp;

    /** @param canOfferXp whether that player may offer XP ({@code mrtrades.xp}) */
    public TradeView(Supplier<Messages> messages, DisplayItems display, Supplier<Settings> settings,
            Predicate<UUID> canOfferXp) {
        this.messages = messages;
        this.display = display;
        this.settings = settings;
        this.canOfferXp = canOfferXp;
    }

    /** Whether {@code side}'s XP button is shown and clickable. */
    public boolean xpShown(Side side) {
        return settings.get().xp().enabled() && canOfferXp.test(side.playerId());
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

        // Money arrives in step 6. XP: your button when you may offer XP; theirs whenever XP is on.
        if (xpShown(own)) {
            contents[GuiLayout.XP] = amountButton(Material.EXPERIENCE_BOTTLE,
                    m.component("gui.xp", amount(own.offer().xpLevels())), List.of(m.component("gui.xp-click")),
                    own.offer().xpLevels());
        }
        if (settings.get().xp().enabled()) {
            contents[GuiLayout.OTHER_XP] = amountButton(Material.EXPERIENCE_BOTTLE,
                    m.component("gui.xp", amount(other.offer().xpLevels())), List.of(), other.offer().xpLevels());
        }
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
            int seconds = Math.max(1, session.countdownRemaining());
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

    // --- Editor ---

    /** A new XP editor for {@code side}, starting from their current offer. */
    public EditorHolder createEditor(TradeSession session, Side side, int maxLevels) {
        EditorHolder holder = new EditorHolder(session, side, EditorHolder.Kind.XP, side.offer().xpLevels());
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.EDITOR_SIZE,
                messages.get().component("gui.xp-editor-title"));
        holder.setInventory(inventory);
        renderEditor(holder, maxLevels);
        return holder;
    }

    /** The +/- amounts of an editor of this kind, smallest first. */
    public List<Integer> editorButtons(EditorHolder.Kind kind) {
        return settings.get().xp().buttons();
    }

    /** Redraws an editor. {@code max} is the most the player can offer right now. */
    public void renderEditor(EditorHolder holder, long max) {
        Messages m = messages.get();
        ItemStack filler = display.button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        ItemStack[] contents = new ItemStack[GuiLayout.EDITOR_SIZE];
        Arrays.fill(contents, filler);
        List<Integer> buttons = editorButtons(holder.kind());
        for (int i = 0; i < buttons.size(); i++) {
            int value = buttons.get(i);
            contents[GuiLayout.minusSlot(i)] = amountButton(Material.RED_STAINED_GLASS_PANE,
                    m.component("gui.editor-minus", amount(value)), List.of(), value);
            contents[GuiLayout.plusSlot(i)] = amountButton(Material.LIME_STAINED_GLASS_PANE,
                    m.component("gui.editor-plus", amount(value)), List.of(), value);
        }
        contents[GuiLayout.EDITOR_CURRENT] = amountButton(Material.EXPERIENCE_BOTTLE,
                m.component("gui.editor-current", amount(holder.draft())), List.of(), holder.draft());
        contents[GuiLayout.EDITOR_BACK] = display.button(Material.ARROW, m.component("gui.editor-back"), List.of());
        // "Type an amount" (chat / Bedrock form) arrives in step 7; its slot stays filler until then.
        contents[GuiLayout.EDITOR_ZERO] = display.button(Material.BUCKET, m.component("gui.editor-zero"), List.of());
        contents[GuiLayout.EDITOR_MAX] = amountButton(Material.HOPPER,
                m.component("gui.editor-max", amount(max)), List.of(), max);
        contents[GuiLayout.EDITOR_DONE] = display.button(Material.LIME_DYE, m.component("gui.editor-done"), List.of());
        holder.getInventory().setContents(contents);
    }

    /** A button whose stack size shows {@code value} (1-64; 0 shows as 1). */
    private ItemStack amountButton(Material material, Component name, List<Component> lore, long value) {
        ItemStack item = display.button(material, name, lore);
        item.setAmount((int) Math.max(1, Math.min(64, value)));
        return item;
    }

    private static TagResolver amount(long value) {
        return Placeholder.unparsed("amount", String.valueOf(value));
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
