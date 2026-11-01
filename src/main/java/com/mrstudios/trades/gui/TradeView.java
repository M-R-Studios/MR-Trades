package com.mrstudios.trades.gui;

import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.hook.EconomyPort;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import com.mrstudios.trades.trade.TradeState;
import java.math.BigDecimal;
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
    private final Supplier<EconomyPort> economy;
    private final Predicate<UUID> canOfferXp;
    private final Predicate<UUID> canOfferMoney;

    /**
     * @param economy the current economy, or null without Vault
     * @param canOfferXp whether that player may offer XP ({@code mrtrades.xp})
     * @param canOfferMoney whether that player may offer money ({@code mrtrades.money})
     */
    public TradeView(Supplier<Messages> messages, DisplayItems display, Supplier<Settings> settings,
            Supplier<EconomyPort> economy, Predicate<UUID> canOfferXp, Predicate<UUID> canOfferMoney) {
        this.messages = messages;
        this.display = display;
        this.settings = settings;
        this.economy = economy;
        this.canOfferXp = canOfferXp;
        this.canOfferMoney = canOfferMoney;
    }

    /** Whether {@code side}'s XP button is shown and clickable. */
    public boolean xpShown(Side side) {
        return settings.get().xp().enabled() && canOfferXp.test(side.playerId());
    }

    /** Whether money trading works right now: on in the config, and an economy behind Vault. */
    public boolean moneyAvailable() {
        EconomyPort port = economy.get();
        return settings.get().money().enabled() && port != null && port.isAvailable();
    }

    /** Whether {@code side}'s money button is shown and clickable. */
    public boolean moneyShown(Side side) {
        return moneyAvailable() && canOfferMoney.test(side.playerId());
    }

    /** {@code amount} of money, written the way the economy writes it. */
    public String formatMoney(BigDecimal amount) {
        EconomyPort port = economy.get();
        try {
            return port != null && port.isAvailable() ? port.format(amount) : amount.toPlainString();
        } catch (RuntimeException e) {
            return amount.toPlainString();
        }
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

        // Your buttons when you may offer money / XP; theirs whenever money / XP trading is on.
        if (moneyShown(own)) {
            contents[GuiLayout.MONEY] = display.button(Material.GOLD_INGOT,
                    m.component("gui.money", amount(formatMoney(own.offer().money()))),
                    List.of(m.component("gui.money-click")));
        }
        if (moneyAvailable()) {
            contents[GuiLayout.OTHER_MONEY] = display.button(Material.GOLD_INGOT,
                    m.component("gui.money", amount(formatMoney(other.offer().money()))), List.of());
        }
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

    /** A new money or XP editor for {@code side}, starting from their current offer. */
    public EditorHolder createEditor(TradeSession session, Side side, EditorHolder.Kind kind, BigDecimal max) {
        boolean money = kind == EditorHolder.Kind.MONEY;
        BigDecimal current = money ? side.offer().money() : BigDecimal.valueOf(side.offer().xpLevels());
        EditorHolder holder = new EditorHolder(session, side, kind, current);
        Inventory inventory = Bukkit.createInventory(holder, GuiLayout.EDITOR_SIZE,
                messages.get().component(money ? "gui.money-editor-title" : "gui.xp-editor-title"));
        holder.setInventory(inventory);
        renderEditor(holder, max);
        return holder;
    }

    /** The +/- amounts of an editor of this kind, smallest first. */
    public List<BigDecimal> editorButtons(EditorHolder.Kind kind) {
        if (kind == EditorHolder.Kind.MONEY) {
            return settings.get().money().buttons();
        }
        return settings.get().xp().buttons().stream().map(BigDecimal::valueOf).toList();
    }

    /** Redraws an editor. {@code max} is the most the player can offer right now. */
    public void renderEditor(EditorHolder holder, BigDecimal max) {
        Messages m = messages.get();
        boolean money = holder.kind() == EditorHolder.Kind.MONEY;
        Material icon = money ? Material.GOLD_INGOT : Material.EXPERIENCE_BOTTLE;
        ItemStack filler = display.button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        ItemStack[] contents = new ItemStack[GuiLayout.EDITOR_SIZE];
        Arrays.fill(contents, filler);
        List<BigDecimal> buttons = editorButtons(holder.kind());
        for (int i = 0; i < buttons.size(); i++) {
            BigDecimal value = buttons.get(i);
            contents[GuiLayout.minusSlot(i)] = amountButton(Material.RED_STAINED_GLASS_PANE,
                    m.component("gui.editor-minus", amount(show(holder, value))), List.of(), value);
            contents[GuiLayout.plusSlot(i)] = amountButton(Material.LIME_STAINED_GLASS_PANE,
                    m.component("gui.editor-plus", amount(show(holder, value))), List.of(), value);
        }
        contents[GuiLayout.EDITOR_CURRENT] = amountButton(icon,
                m.component("gui.editor-current", amount(show(holder, holder.draft()))), List.of(),
                money ? BigDecimal.ONE : holder.draft());
        contents[GuiLayout.EDITOR_BACK] = display.button(Material.ARROW, m.component("gui.editor-back"), List.of());
        // "Type an amount" (chat / Bedrock form) arrives in step 7; its slot stays filler until then.
        contents[GuiLayout.EDITOR_ZERO] = display.button(Material.BUCKET, m.component("gui.editor-zero"), List.of());
        contents[GuiLayout.EDITOR_MAX] = amountButton(Material.HOPPER,
                m.component("gui.editor-max", amount(show(holder, max))), List.of(), money ? BigDecimal.ONE : max);
        contents[GuiLayout.EDITOR_DONE] = display.button(Material.LIME_DYE, m.component("gui.editor-done"), List.of());
        holder.getInventory().setContents(contents);
    }

    private String show(EditorHolder holder, BigDecimal value) {
        return holder.kind() == EditorHolder.Kind.MONEY ? formatMoney(value) : value.toPlainString();
    }

    /** A button whose stack size shows {@code value} (1-64; 0 shows as 1). */
    private ItemStack amountButton(Material material, Component name, List<Component> lore, BigDecimal value) {
        ItemStack item = display.button(material, name, lore);
        item.setAmount(value.max(BigDecimal.ONE).min(BigDecimal.valueOf(64)).intValue());
        return item;
    }

    private ItemStack amountButton(Material material, Component name, List<Component> lore, long value) {
        return amountButton(material, name, lore, BigDecimal.valueOf(value));
    }

    private static TagResolver amount(long value) {
        return Placeholder.unparsed("amount", String.valueOf(value));
    }

    private static TagResolver amount(String value) {
        return Placeholder.unparsed("amount", value);
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
