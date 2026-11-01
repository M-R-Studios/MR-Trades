package com.mrstudios.trades.trade;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.Settings.SoundSetting;
import com.mrstudios.trades.gui.DisplayItems;
import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.gui.TradeHolder;
import com.mrstudios.trades.gui.TradeView;
import com.mrstudios.trades.hook.BedrockForms;
import com.mrstudios.trades.history.TradeLog;
import com.mrstudios.trades.history.TradeRecord;
import com.mrstudios.trades.hook.EconomyPort;
import com.mrstudios.trades.input.AmountParser;
import com.mrstudios.trades.input.ChatInput;
import com.mrstudios.trades.returns.PlayerStorage;
import com.mrstudios.trades.returns.ReturnService;
import com.mrstudios.trades.rules.ClickAction;
import com.mrstudios.trades.rules.LimitsCheck;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import com.mrstudios.trades.trade.TradeSession.ChangeResult;
import com.mrstudios.trades.trade.TradeSession.ConfirmResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs trades on the server: opens the windows, moves items between inventories and offers
 * (keeping the escrow record in step), and ends every trade through one idempotent path
 * ({@link #abort}, and later completion). Main thread only.
 */
public final class TradeService {

    public static final String BYPASS_DISTANCE = "mrtrades.bypass.distance";
    public static final String BYPASS_WORLD = "mrtrades.bypass.world";

    private final MRTrades plugin;
    private final TradeRegistry registry;
    private final ReturnService returns;
    private final TradeView view;
    private final DisplayItems display;
    /** Items whose owner was somehow offline when returned; queued for them on join. Should stay empty. */
    private final Map<UUID, List<ItemStack>> orphans = new HashMap<>();
    private final Supplier<EconomyPort> economy;
    private final Supplier<BedrockForms> bedrock;
    private final ChatInput input;
    /** The trade log, or null when history is off. */
    private final Supplier<TradeLog> history;
    /**
     * Bedrock clients (through Geyser) close their old screen when the server swaps one window for
     * another, and that "closed" arrives after the new window is open; the server then closes the
     * new one. So for Bedrock players a switch is: close, wait this many ticks, then open.
     */
    public static final int BEDROCK_SWITCH_TICKS = 4;
    /** A Bedrock "closed" this soon after the plugin opened a window is taken as stray. */
    static final int STRAY_CLOSE_TICKS = 6;
    /** At most this many stray closes are absorbed in a row; after that a close is real. */
    private static final int MAX_STRAY_CLOSES = 2;
    /** Tick the plugin last opened a trade window or editor for each player. */
    private final Map<UUID, Integer> openedAt = new HashMap<>();
    private final Map<UUID, Integer> strayCloses = new HashMap<>();
    private BukkitTask limitsTask;
    private BukkitTask watchdogTask;
    private boolean shuttingDown;

    public TradeService(MRTrades plugin, TradeRegistry registry, ReturnService returns, TradeView view,
            DisplayItems display, Supplier<EconomyPort> economy, Supplier<BedrockForms> bedrock, ChatInput input,
            Supplier<TradeLog> history) {
        this.plugin = plugin;
        this.registry = registry;
        this.returns = returns;
        this.view = view;
        this.display = display;
        this.economy = economy;
        this.bedrock = bedrock;
        this.input = input;
        this.history = history;
    }

    public TradeRegistry registry() {
        return registry;
    }

    public DisplayItems display() {
        return display;
    }

    /** Whether {@code side}'s XP button is shown and works. */
    public boolean xpShown(Side side) {
        return view.xpShown(side);
    }

    /** Whether {@code side}'s money button is shown and works. */
    public boolean moneyShown(Side side) {
        return view.moneyShown(side);
    }

    public int editorButtonCount(EditorHolder editor) {
        return view.editorButtons(editor.kind()).size();
    }

    private Settings settings() {
        return plugin.state().settings();
    }

    private Messages messages() {
        return plugin.state().messages();
    }

    // --- Start ---

    /**
     * Starts a trade between {@code a} (who sent the request) and {@code b} and opens both windows.
     * The caller has already checked both players.
     */
    public TradeSession start(Player a, Player b) {
        TradeSession session = new TradeSession(UUID.randomUUID(), a.getUniqueId(), a.getName(), b.getUniqueId(),
                b.getName(), System::currentTimeMillis);
        if (!registry.register(session)) {
            throw new IllegalStateException(a.getName() + " or " + b.getName() + " is already trading");
        }
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            side.setView(ViewState.TRADE);
            opened(player, view.create(session, side).getInventory());
            messages().send(player, "trade-started", Placeholder.unparsed("player", session.other(side).name()));
            play(player, settings().sounds().open());
        }
        return session;
    }

    // --- Offer changes ---

    /** Moves items from {@code player}'s inventory slot into their offer (DESIGN.md 3.5 #2). */
    public void addFromInventory(Player player, TradeSession session, Side side, int playerSlot, boolean wholeStack) {
        if (!session.acceptsChanges()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        // Re-read the slot; never trust the item the event reports.
        ItemStack item = inventory.getItem(playerSlot);
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        if (display.isDisplay(item)) {
            inventory.setItem(playerSlot, null);
            plugin.getLogger().warning("Removed a trade display copy from " + player.getName() + "'s inventory (slot "
                    + playerSlot + "). Display copies should never leave a trade window.");
            return;
        }
        int wanted = wholeStack ? item.getAmount() : 1;
        int moving = Math.min(wanted, side.offer().roomFor(item));
        if (moving <= 0) {
            messages().send(player, "offer-full");
            return;
        }
        ItemStack moved = item.clone();
        moved.setAmount(moving);
        ItemStack remainder = item.clone();
        remainder.setAmount(item.getAmount() - moving);
        // Out of the inventory and into the offer in the same handler: never in two places.
        inventory.setItem(playerSlot, remainder.getAmount() > 0 ? remainder : null);
        ChangeResult result = session.addItem(side, settings().trade().changeLockMs(), moved);
        if (result != ChangeResult.CHANGED) {
            // Can't happen after the checks above; put it straight back if it ever does.
            inventory.setItem(playerSlot, item);
            plugin.getLogger().warning("An offer refused " + moved + " from " + player.getName() + " (" + result
                    + "); it was put back.");
            return;
        }
        offerChanged(session, side);
    }

    /** Moves items from {@code player}'s offer back into their inventory (DESIGN.md 3.5 #3). */
    public void returnFromOffer(Player player, TradeSession session, Side side, int index, boolean wholeStack) {
        if (!session.acceptsChanges()) {
            return;
        }
        ItemStack stack = side.offer().item(index);
        if (stack == null) {
            return;
        }
        ItemStack part = stack.clone();
        part.setAmount(wholeStack ? stack.getAmount() : 1);
        PlayerInventory inventory = player.getInventory();
        if (!InventorySimulator.fits(ItemStackView.of(storage(inventory)), List.of(new ItemStackView(part)),
                inventory.getMaxStackSize())) {
            messages().send(player, "inventory-full");
            return;
        }
        ItemStack taken = session.removeItem(side, settings().trade().changeLockMs(), index, part.getAmount());
        if (taken == null) {
            return;
        }
        ItemStack rest = PlayerStorage.add(inventory, taken);
        if (rest != null) {
            // The simulator said it fits, so this can't happen; if it ever does, the rest is kept safe.
            returns.queue(player, List.of(rest));
            plugin.getLogger().warning("Items returned from an offer didn't all fit in " + player.getName()
                    + "'s inventory; the rest went to their return queue.");
            messages().send(player, "returns-queued", Placeholder.unparsed("count", "1"));
        }
        offerChanged(session, side);
    }

    /** After a change: escrow record, both windows, sound, and a redraw when the change lock ends. */
    private void offerChanged(TradeSession session, Side side) {
        Player owner = player(side);
        if (owner != null) {
            returns.store().writeEscrow(owner, session.id(), side.offer().items());
        }
        render(session);
        playBoth(session, settings().sounds().change());
        long lockMs = settings().trade().changeLockMs();
        if (lockMs > 0 && !shuttingDown) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!session.isEnded()) {
                    render(session);
                }
            }, lockMs / 50 + 1);
        }
    }

    // --- Buttons ---

    public void toggleConfirm(Player player, TradeSession session, Side side) {
        ConfirmResult result = session.toggleConfirm(side);
        switch (result) {
            case LOCKED -> messages().send(player, "confirm-locked",
                    Placeholder.unparsed("player", session.other(side).name()));
            case CONFIRMED, BOTH_CONFIRMED -> playBoth(session, settings().sounds().confirm());
            default -> {
            }
        }
        if (result == ConfirmResult.BOTH_CONFIRMED) {
            startCountdown(session);
            return;
        }
        render(session);
    }

    // --- Countdown and completion ---

    /**
     * Both confirmed: counts down {@code trade.countdown-seconds}, once a second, then completes.
     * Any change, unconfirm or editor opening stops it through the session.
     */
    private void startCountdown(TradeSession session) {
        int seconds = settings().trade().countdownSeconds();
        if (seconds <= 0) {
            complete(session);
            return;
        }
        session.setCountdownRemaining(seconds);
        render(session);
        playBoth(session, settings().sounds().countdown());
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> countdownTick(session), 20L, 20L);
        session.attachCountdown(task::cancel);
    }

    private void countdownTick(TradeSession session) {
        if (session.state() != TradeState.COUNTDOWN) {
            return; // Stopped; the session cancels the task.
        }
        int left = session.countdownRemaining() - 1;
        session.setCountdownRemaining(left);
        if (left <= 0) {
            complete(session);
        } else {
            render(session);
            playBoth(session, settings().sounds().countdown());
        }
    }

    /**
     * Atomic completion (DESIGN.md 3.5.1), on the main thread in one call. Re-checks everything,
     * then moves XP and items in the same tick. If a check fails the trade either stays open with
     * both confirmations cleared (space, levels) or ends with everything returned (a player left,
     * died, moved away or changed game mode).
     */
    public void complete(TradeSession session) {
        // 1. Guard: from here on nothing can change the offers or re-enter.
        if (!session.beginCompleting()) {
            return;
        }
        Player a = player(session.a());
        Player b = player(session.b());

        // 2. Re-validate.
        CompletionValidator.Result check = CompletionValidator.validate(snapshot(session, a, b));
        if (!check.ok()) {
            completionRefused(session, check);
            return;
        }

        // 4. Money: only the net difference moves; a failed deposit is refunded.
        if (session.a().offer().money().signum() != 0 || session.b().offer().money().signum() != 0) {
            MoneyTransfer.Result paid = MoneyTransfer.transfer(economy.get(), session.a().playerId(),
                    session.a().offer().money(), session.b().playerId(), session.b().offer().money());
            if (!paid.ok()) {
                moneyFailed(session, paid);
                return;
            }
        }

        boolean itemsTaken = false;
        List<ItemStack> fromA = List.of();
        List<ItemStack> fromB = List.of();
        try {
            // 5. XP: whole levels; checked above, so it can't fail.
            int aXp = session.a().offer().xpLevels();
            int bXp = session.b().offer().xpLevels();
            if (aXp != 0 || bXp != 0) {
                a.setLevel(a.getLevel() - aXp + bXp);
                b.setLevel(b.getLevel() - bXp + aXp);
            }

            // 6. Items: out of both offers, escrow records cleared, into the other player's storage.
            fromA = session.takeItems(session.a());
            fromB = session.takeItems(session.b());
            itemsTaken = true;
            returns.store().clearEscrow(a);
            returns.store().clearEscrow(b);
            hand(b, fromA);
            hand(a, fromB);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Completing trade " + session.id() + " failed"
                    + (itemsTaken ? " while handing over items" : "; everything goes back to its owner"), e);
            if (!itemsTaken) {
                abort(session, EndReason.CANCELLED, null, Set.of());
                return;
            }
        }

        // 7. Finish.
        end(session);
        for (Player player : List.of(a, b)) {
            try {
                // So a crash right after can't roll one player back to before the trade (3.6).
                player.saveData();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't save " + player.getName() + " after a trade", e);
            }
        }
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            messages().send(player, "trade-complete", Placeholder.unparsed("player", session.other(side).name()));
            play(player, settings().sounds().complete());
        }
        // 8. History (DESIGN.md 6.3): built here, written on another thread; can't affect the trade.
        log(session, a, session.id(), fromA, fromB, false);
    }

    /**
     * Queues the history record. Items are serialized here on the main thread. Any failure is
     * only logged: the trade has already happened.
     */
    private void log(TradeSession session, Player a, UUID id, List<ItemStack> fromA, List<ItemStack> fromB,
            boolean economyError) {
        TradeLog log = history.get();
        if (log == null || !settings().history().enabled()) {
            return;
        }
        try {
            Location at = a.getLocation();
            Side sa = session.a();
            Side sb = session.b();
            log.record(new TradeRecord(id, System.currentTimeMillis(), settings().history().effectiveServerName(),
                    at.getWorld() == null ? "" : at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                    sa.playerId(), sa.name(), sb.playerId(), sb.name(),
                    TradeRecord.serialize(fromA), TradeRecord.serialize(fromB),
                    TradeRecord.summarize(fromA), TradeRecord.summarize(fromB),
                    sa.offer().money(), sb.offer().money(), sa.offer().xpLevels(), sb.offer().xpLevels(),
                    economyError, dataVersion()));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't record trade " + id + " in the trade history", e);
        }
    }

    @SuppressWarnings("deprecation") // getUnsafe: the only source of the data version
    private static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Gives {@code items} to {@code receiver}; anything that doesn't fit goes to their return queue. */
    private void hand(Player receiver, List<ItemStack> items) {
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack item : items) {
            try {
                ItemStack rest = PlayerStorage.add(receiver.getInventory(), item);
                if (rest != null) {
                    leftover.add(rest);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't hand " + item + " to " + receiver.getName(), e);
                leftover.add(item);
            }
        }
        if (!leftover.isEmpty()) {
            // Only possible if the space check and the move disagree; never deleted.
            plugin.getLogger().warning(leftover.size() + " traded stack(s) didn't fit in " + receiver.getName()
                    + "'s inventory and went to their return queue.");
            returns.queue(receiver, leftover);
            messages().send(receiver, "returns-queued", Placeholder.unparsed("count", String.valueOf(leftover.size())));
        }
    }

    /**
     * The economy refused or failed. Nothing else has happened yet (XP and items come after), so
     * the trade goes back to OPEN like any failed check. If even the refund failed, the payer lost
     * the amount: that's logged loudly and both players are told to contact staff.
     */
    private void moneyFailed(TradeSession session, MoneyTransfer.Result paid) {
        Side payer = paid.payer() == null ? null : session.side(paid.payer());
        String payerName = payer == null ? "" : payer.name();
        if (paid.status() == MoneyTransfer.Status.ECONOMY_ERROR) {
            Side payee = session.side(paid.payee());
            plugin.getLogger().severe("Economy error in trade " + session.id() + ": " + paymentText(paid, payerName,
                    payee == null ? "?" : payee.name()) + " was withdrawn, but neither the deposit nor the refund"
                    + " worked (" + paid.errors() + "). The payer must be refunded by hand.");
            // Recorded with its own id (no items moved; the trade stays open and may still complete).
            Player a = player(session.a());
            if (a != null) {
                log(session, a, UUID.randomUUID(), List.of(), List.of(), true);
            }
        } else {
            plugin.getLogger().warning("Trade " + session.id() + ": the economy refused to move "
                    + paid.amount().toPlainString() + " (" + paid.errors() + "); nothing was moved.");
        }
        session.completionFailed();
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            if (player == null) {
                continue;
            }
            if (paid.status() == MoneyTransfer.Status.ECONOMY_ERROR) {
                messages().send(player, "economy-error", Placeholder.unparsed("id", session.id().toString()));
            } else {
                messages().send(player, "complete-failed-money", Placeholder.unparsed("player", payerName));
            }
        }
        render(session);
    }

    private static String paymentText(MoneyTransfer.Result paid, String payer, String payee) {
        return paid.amount().toPlainString() + " from " + payer + " to " + payee;
    }

    /**
     * DESIGN.md 3.5 #17: the economy went away. Every money offer is set to 0 (an offer change, so
     * both confirmations reset) and both players are told.
     */
    public void economyCheck() {
        if (shuttingDown || view.moneyAvailable()) {
            return;
        }
        for (TradeSession session : registry.sessions()) {
            if (!session.acceptsChanges()) {
                continue;
            }
            boolean changed = false;
            for (Side side : List.of(session.a(), session.b())) {
                if (session.setMoney(side, 0, BigDecimal.ZERO) == TradeSession.ChangeResult.CHANGED) {
                    changed = true;
                }
            }
            if (changed) {
                for (Side side : List.of(session.a(), session.b())) {
                    Player player = player(side);
                    if (player != null) {
                        messages().send(player, "economy-unavailable");
                    }
                }
                render(session);
            }
        }
    }

    /** Step 3: a failed check either keeps the trade open (confirm again) or ends it. */
    private void completionRefused(TradeSession session, CompletionValidator.Result check) {
        UUID who = check.player();
        if (check.reason().abortsTrade()) {
            EndReason reason = switch (check.reason()) {
                case PLAYER_OFFLINE -> EndReason.QUIT;
                case PLAYER_DEAD -> EndReason.DIED;
                case BLOCKED_GAMEMODE -> EndReason.GAMEMODE;
                case TOO_FAR -> EndReason.TOO_FAR;
                default -> EndReason.WORLD;
            };
            boolean died = check.reason() == CompletionValidator.Reason.PLAYER_DEAD && who != null;
            abort(session, reason, who, died ? Set.of(who) : Set.of());
            return;
        }
        session.completionFailed();
        String key = switch (check.reason()) {
            case NO_SPACE -> "complete-failed-space";
            case NO_XP -> "complete-failed-xp";
            case NO_MONEY, ECONOMY_UNAVAILABLE -> "complete-failed-money";
            default -> null;
        };
        if (key != null) {
            Side side = who == null ? null : session.side(who);
            String name = side == null ? "" : side.name();
            for (Side each : List.of(session.a(), session.b())) {
                Player player = player(each);
                if (player != null) {
                    messages().send(player, key, Placeholder.unparsed("player", name));
                }
            }
        }
        render(session);
    }

    private CompletionSnapshot snapshot(TradeSession session, Player a, Player b) {
        boolean bypassDistance = a != null && a.hasPermission(BYPASS_DISTANCE)
                || b != null && b.hasPermission(BYPASS_DISTANCE);
        return new CompletionSnapshot(session.version(), trader(session.a(), a), trader(session.b(), b),
                settings().limits(), bypassDistance, economy.get(),
                a != null ? a.getInventory().getMaxStackSize() : 64);
    }

    private CompletionSnapshot.Trader trader(Side side, Player player) {
        if (player == null || !player.isOnline()) {
            return new CompletionSnapshot.Trader(side.playerId(), false, false, null, false, null,
                    side.confirmedAtVersion(), 0, List.of(), side.offer().itemViews(), side.offer().money(),
                    side.offer().xpLevels());
        }
        return new CompletionSnapshot.Trader(side.playerId(), true, player.getHealth() <= 0, player.getGameMode(),
                player.hasPermission(BYPASS_WORLD), position(player), side.confirmedAtVersion(), player.getLevel(),
                new ArrayList<>(ItemStackView.of(storage(player.getInventory()))), side.offer().itemViews(),
                side.offer().money(), side.offer().xpLevels());
    }

    // --- XP editor ---

    /** The XP button: opens the XP editor. */
    public void openXpEditor(Player player, TradeSession session, Side side) {
        openEditor(player, session, side, EditorHolder.Kind.XP);
    }

    /** The money button: opens the money editor. */
    public void openMoneyEditor(Player player, TradeSession session, Side side) {
        openEditor(player, session, side, EditorHolder.Kind.MONEY);
    }

    /** The most {@code player} can offer of this kind right now: their level, or their balance capped by money.max-amount. */
    private BigDecimal editorMax(Player player, EditorHolder.Kind kind) {
        if (kind == EditorHolder.Kind.XP) {
            return BigDecimal.valueOf(player.getLevel());
        }
        EconomyPort port = economy.get();
        if (port == null || !port.isAvailable()) {
            return BigDecimal.ZERO;
        }
        BigDecimal max;
        try {
            max = MoneyTransfer.round(port.balance(player.getUniqueId()).max(BigDecimal.ZERO), port.fractionalDigits());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't read " + player.getName() + "'s balance", e);
            return BigDecimal.ZERO;
        }
        BigDecimal cap = settings().money().maxAmount();
        return cap.signum() > 0 ? max.min(cap) : max;
    }

    /** Clears your own confirmation and swaps the window for the editor next tick. */
    private void openEditor(Player player, TradeSession session, Side side, EditorHolder.Kind kind) {
        boolean shown = kind == EditorHolder.Kind.XP ? view.xpShown(side) : view.moneyShown(side);
        // Only from the trade window: a second tap before the editor opens must not open it twice
        // (the second open would close the first editor and end the trade).
        if (!session.acceptsChanges() || !shown || side.view() != ViewState.TRADE) {
            return;
        }
        session.clearConfirmation(side);
        // Set before the window changes, so the close of the trade window isn't taken as Esc.
        side.setView(ViewState.REOPENING);
        render(session);
        switchWindow(player, () -> {
            if (session.isEnded() || !player.isOnline() || side.view() != ViewState.REOPENING) {
                return;
            }
            opened(player, view.createEditor(session, side, kind, editorMax(player, kind)).getInventory());
            side.setView(ViewState.EDITOR);
        });
    }

    /** A button in the editor. Only "Done" changes the offer. */
    public void editorPress(Player player, EditorHolder editor, ClickAction.Press press) {
        TradeSession session = editor.session();
        Side side = editor.side();
        // After Back, Done or "Type an amount" the editor is on its way out: further taps (a quick
        // double tap) are ignored, or they'd queue a second window switch that ends the trade.
        if (session.isEnded() || side.view() != ViewState.EDITOR) {
            return;
        }
        BigDecimal max = editorMax(player, editor.kind());
        List<BigDecimal> buttons = view.editorButtons(editor.kind());
        BigDecimal draft = editor.draft();
        switch (press.button()) {
            case EDITOR_MINUS -> draft = draft.subtract(buttons.get(press.index()));
            case EDITOR_PLUS -> draft = draft.add(buttons.get(press.index()));
            case EDITOR_ZERO -> draft = BigDecimal.ZERO;
            case EDITOR_MAX -> draft = max;
            case EDITOR_DONE -> {
                BigDecimal amount = draft.max(BigDecimal.ZERO).min(max);
                long lockMs = settings().trade().changeLockMs();
                TradeSession.ChangeResult result = editor.kind() == EditorHolder.Kind.XP
                        ? session.setXpLevels(side, lockMs, amount.intValue())
                        : session.setMoney(side, lockMs, moneyAmount(amount));
                if (result == TradeSession.ChangeResult.CHANGED) {
                    offerChanged(session, side);
                }
                reopenTrade(player, session, side);
                return;
            }
            case EDITOR_BACK -> {
                reopenTrade(player, session, side);
                return;
            }
            case EDITOR_TYPE -> {
                startInput(player, editor, max);
                return;
            }
            default -> {
                return;
            }
        }
        editor.setDraft(draft.max(BigDecimal.ZERO).min(max));
        view.renderEditor(editor, max);
    }

    /** A money amount rounded down to the economy's decimals, without trailing zeros noise. */
    private BigDecimal moneyAmount(BigDecimal amount) {
        EconomyPort port = economy.get();
        int digits = port == null ? -1 : port.fractionalDigits();
        BigDecimal rounded = MoneyTransfer.round(amount, digits);
        return rounded.signum() == 0 ? BigDecimal.ZERO : rounded;
    }

    /** Esc in the editor discards the draft and goes back to the trade window; it doesn't end the trade. */
    public void editorClosed(Player player, EditorHolder editor) {
        TradeSession session = editor.session();
        if (!session.isEnded() && editor.side().view() == ViewState.EDITOR) {
            reopenTrade(player, session, editor.side());
        }
    }

    // --- Typed amounts: chat and Bedrock forms (DESIGN.md 4.2) ---

    /**
     * "Type an amount": the window closes and the player types in chat, or (Bedrock with
     * Floodgate and {@code bedrock.amount-form}) answers a form. The view state is set first, so
     * the close isn't taken as Esc. The trade stays live the whole time.
     */
    private void startInput(Player player, EditorHolder editor, BigDecimal max) {
        TradeSession session = editor.session();
        Side side = editor.side();
        BedrockForms forms = bedrockForms(player, settings().bedrock().amountForm());
        boolean form = forms != null;
        side.setView(form ? ViewState.INPUT_FORM : ViewState.INPUT_CHAT);
        UUID token = UUID.randomUUID();
        int seconds = settings().input().chatTimeoutSeconds();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> inputTimedOut(player, token),
                seconds * 20L);
        input.start(player.getUniqueId(),
                new ChatInput.Pending(session, side, editor.kind(), token, form, timeout::cancel));
        render(session);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (input.get(player.getUniqueId()) == null || !player.isOnline()) {
                return;
            }
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            if (form && sendAmountForm(forms, player, editor.kind(), side, max, token)) {
                return;
            }
            if (form) {
                // The form couldn't be sent: fall back to chat, which works for everyone.
                side.setView(ViewState.INPUT_CHAT);
                input.switchToChat(player.getUniqueId(), token);
            }
            messages().send(player, "input-chat-prompt",
                    Placeholder.unparsed("cancel-word", settings().input().cancelWord()),
                    Placeholder.unparsed("seconds", String.valueOf(seconds)));
        });
    }

    private boolean sendAmountForm(BedrockForms forms, Player player, EditorHolder.Kind kind, Side side,
            BigDecimal max, UUID token) {
        UUID id = player.getUniqueId();
        Runnable closed = () -> Bukkit.getScheduler().runTask(plugin, () -> inputCancelled(player, token));
        if (kind == EditorHolder.Kind.XP) {
            String title = plain("form-xp-title");
            return forms.sendSlider(id, title, title, max.intValue(), side.offer().xpLevels(),
                    levels -> Bukkit.getScheduler().runTask(plugin, () -> typedAmount(player, token,
                            String.valueOf(levels), true)), closed);
        }
        String title = plain("form-money-title");
        return forms.sendInput(id, title, title, side.offer().money().toPlainString(),
                text -> Bukkit.getScheduler().runTask(plugin, () -> typedAmount(player, token, text, true)), closed);
    }

    /** A chat message from a player who is typing an amount (main thread). */
    public void chatAmount(Player player, String text) {
        ChatInput.Pending wait = input.get(player.getUniqueId());
        if (wait != null && !wait.form()) {
            typedAmount(player, wait.token(), text, false);
        }
    }

    /**
     * Handles typed text for the wait with {@code token}. Chat: an invalid amount keeps waiting
     * with an error. Form: anything invalid reopens the window unchanged.
     */
    private void typedAmount(Player player, UUID token, String text, boolean fromForm) {
        ChatInput.Pending wait = input.get(player.getUniqueId());
        if (wait == null || !wait.token().equals(token) || wait.session().isEnded()) {
            return; // A late answer: timed out, cancelled or the trade ended.
        }
        String typed = text.strip();
        String cancelWord = settings().input().cancelWord();
        if (typed.equalsIgnoreCase(cancelWord)) {
            inputCancelled(player, token);
            return;
        }
        boolean money = wait.kind() == EditorHolder.Kind.MONEY;
        BigDecimal max = editorMax(player, wait.kind());
        AmountParser.Result result;
        if (typed.equalsIgnoreCase("all")) {
            result = new AmountParser.Valid(max);
        } else if (money) {
            EconomyPort port = economy.get();
            result = AmountParser.parseMoney(typed, port == null ? -1 : port.fractionalDigits(), max);
        } else {
            result = AmountParser.parseXp(typed, max.intValue());
        }
        switch (result) {
            case AmountParser.Valid valid -> {
                if (input.finish(player.getUniqueId(), token) == null) {
                    return;
                }
                long lockMs = settings().trade().changeLockMs();
                TradeSession session = wait.session();
                TradeSession.ChangeResult change = money
                        ? session.setMoney(wait.side(), lockMs, moneyAmount(valid.amount()))
                        : session.setXpLevels(wait.side(), lockMs, valid.amount().intValue());
                if (change == TradeSession.ChangeResult.CHANGED) {
                    offerChanged(session, wait.side());
                }
                reopenTrade(player, session, wait.side());
            }
            case AmountParser.TooHigh tooHigh -> {
                messages().send(player, "input-too-high", Placeholder.unparsed("max",
                        money ? view.formatMoney(tooHigh.max()) : tooHigh.max().toPlainString()));
                if (fromForm) {
                    inputCancelled(player, token);
                }
            }
            case AmountParser.Invalid invalid -> {
                messages().send(player, "input-invalid", Placeholder.unparsed("input", invalid.input()),
                        Placeholder.unparsed("cancel-word", cancelWord));
                if (fromForm) {
                    inputCancelled(player, token);
                }
            }
        }
    }

    /** The cancel word, or a closed form: back to the trade window, offer unchanged. */
    private void inputCancelled(Player player, UUID token) {
        ChatInput.Pending wait = input.finish(player.getUniqueId(), token);
        if (wait != null && !wait.session().isEnded()) {
            reopenTrade(player, wait.session(), wait.side());
        }
    }

    private void inputTimedOut(Player player, UUID token) {
        ChatInput.Pending wait = input.finish(player.getUniqueId(), token);
        if (wait != null && !wait.session().isEnded()) {
            messages().send(player, "input-timeout");
            reopenTrade(player, wait.session(), wait.side());
        }
    }

    /** Floodgate's forms for {@code player} if they're on Bedrock and forms are on, else null. */
    public BedrockForms bedrockForms(Player player, boolean formSetting) {
        BedrockForms forms = bedrock.get();
        if (forms == null || !formSetting || !settings().bedrock().floodgate()) {
            return null;
        }
        return forms.isBedrock(player.getUniqueId()) ? forms : null;
    }

    /** A message rendered to plain text, for Bedrock forms (they don't take Adventure components). */
    private String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(messages().component(key));
    }

    /**
     * Swaps the player's window: next tick for Java players; for Bedrock players the current
     * window closes next tick and {@code open} runs {@link #BEDROCK_SWITCH_TICKS} after that.
     * The caller has already set the side's view to REOPENING or an input state, so the close is
     * never taken as the player's.
     */
    private void switchWindow(Player player, Runnable open) {
        if (!isBedrock(player)) {
            Bukkit.getScheduler().runTask(plugin, open);
            return;
        }
        // Never close inside an inventory event (we're usually in a click handler): next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            Bukkit.getScheduler().runTaskLater(plugin, open, BEDROCK_SWITCH_TICKS);
        });
    }

    /** Opens one of our windows and remembers when, for {@link #isStrayClose}. */
    private void opened(Player player, Inventory inventory) {
        player.openInventory(inventory);
        openedAt.put(player.getUniqueId(), Bukkit.getCurrentTick());
    }

    /** Whether Floodgate says {@code player} is on Bedrock (and Floodgate use is on). */
    public boolean isBedrock(Player player) {
        BedrockForms forms = bedrock.get();
        return forms != null && settings().bedrock().floodgate() && forms.isBedrock(player.getUniqueId());
    }

    /**
     * Whether a "player closed the window" right now is a Bedrock client's late close of the
     * previous screen rather than the player's: it comes within {@link #STRAY_CLOSE_TICKS} of the
     * plugin opening a window. Counted, so a client that really keeps closing still gets out.
     */
    public boolean isStrayClose(Player player) {
        Integer at = openedAt.get(player.getUniqueId());
        if (at == null || !isBedrock(player) || Bukkit.getCurrentTick() - at > STRAY_CLOSE_TICKS) {
            strayCloses.remove(player.getUniqueId());
            return false;
        }
        int count = strayCloses.merge(player.getUniqueId(), 1, Integer::sum);
        return count <= MAX_STRAY_CLOSES;
    }

    /** A stray close of the trade window: the trade goes on and the window comes back. */
    public void recoverStrayClose(Player player, TradeSession session, Side side) {
        plugin.getLogger().fine("Ignored a stray window close from " + player.getName() + " (Bedrock); reopening.");
        reopenTrade(player, session, side);
    }

    private void reopenTrade(Player player, TradeSession session, Side side) {
        side.setView(ViewState.REOPENING);
        switchWindow(player, () -> {
            // Only the first queued switch runs; anything that happened since wins.
            if (session.isEnded() || !player.isOnline() || side.view() != ViewState.REOPENING) {
                return;
            }
            opened(player, view.create(session, side).getInventory());
            side.setView(ViewState.TRADE);
            render(session);
        });
    }

    // --- Ending ---

    /**
     * Ends {@code session} without completing it: every item goes back to its owner (DESIGN.md
     * 3.5.2). Idempotent: a second call does nothing.
     *
     * @param actor the player the reason is about, or null
     * @param dead players who are dying: their items go to the return queue
     */
    public void abort(TradeSession session, EndReason reason, UUID actor, Set<UUID> dead) {
        if (!end(session)) {
            return;
        }
        String actorName = actor == null ? "" : session.side(actor) != null ? session.side(actor).name() : "";
        Component why = messages().component(reason.messageKey(), Placeholder.unparsed("player", actorName));
        for (Side side : List.of(session.a(), session.b())) {
            try {
                giveBack(session, side, dead.contains(side.playerId()));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Returning " + side.name() + "'s offer in trade " + session.id()
                        + " failed", e);
            }
        }
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            if (player != null) {
                messages().send(player, "trade-cancelled", Placeholder.component("reason", why));
                play(player, settings().sounds().cancel());
            }
        }
    }

    private void giveBack(TradeSession session, Side side, boolean dead) {
        List<ItemStack> items = session.takeItems(side);
        Player owner = player(side);
        if (owner == null) {
            if (!items.isEmpty()) {
                orphans.computeIfAbsent(side.playerId(), id -> new ArrayList<>()).addAll(items);
                plugin.getLogger().severe(side.name() + " was offline when trade " + session.id() + " ended; "
                        + items.size() + " stack(s) are held for them and queued when they join.");
            }
            return;
        }
        returns.giveBack(owner, items, dead);
        returns.store().clearEscrow(owner);
        if (!dead) {
            returns.deliverQueue(owner);
        }
    }

    /**
     * Marks the trade ended and cleans up everything about it (DESIGN.md 3.7). Every way a trade
     * ends goes through here.
     *
     * @return false if it had already ended
     */
    private boolean end(TradeSession session) {
        if (!session.end()) {
            return false;
        }
        try {
            for (Side side : List.of(session.a(), session.b())) {
                side.setView(ViewState.TRADE);
            }
        } finally {
            registry.remove(session);
            input.clear(session.a().playerId());
            input.clear(session.b().playerId());
            for (UUID id : List.of(session.a().playerId(), session.b().playerId())) {
                openedAt.remove(id);
                strayCloses.remove(id);
            }
            for (Side side : List.of(session.a(), session.b())) {
                Player player = player(side);
                if (player != null) {
                    closeLater(player, session);
                }
            }
        }
        return true;
    }

    /** Closes {@code player}'s window next tick, if it's still this trade's window. */
    private void closeLater(Player player, TradeSession session) {
        Runnable close = () -> {
            if (!player.isOnline()) {
                return;
            }
            if (isOwnView(player, session)) {
                player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
            removeDisplayCopies(player);
            player.updateInventory();
        };
        if (shuttingDown || !plugin.isEnabled()) {
            close.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, close);
        }
    }

    /** Self-check at the end of every trade: no display copy may be left anywhere on the player. */
    private void removeDisplayCopies(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (display.isDisplay(contents[slot])) {
                inventory.setItem(slot, null);
                plugin.getLogger().warning("Removed a trade display copy from " + player.getName()
                        + "'s inventory after a trade.");
            }
        }
        if (display.isDisplay(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            plugin.getLogger().warning("Removed a trade display copy from " + player.getName() + "'s cursor.");
        }
    }

    /** Ends every trade, returning everything (server stop or plugin disable). */
    public void abortAll(EndReason reason) {
        shuttingDown = true;
        stopTasks();
        for (TradeSession session : registry.sessions()) {
            abort(session, reason, null, Set.of());
        }
    }

    /** {@code /mrtrades cancel}: true if the player was trading. */
    public boolean adminCancel(UUID player) {
        TradeSession session = registry.get(player);
        if (session == null) {
            return false;
        }
        abort(session, EndReason.ADMIN, null, Set.of());
        return true;
    }

    /** Hands out items held for a player who was offline when their trade ended. */
    public void claimOrphans(Player player) {
        List<ItemStack> held = orphans.remove(player.getUniqueId());
        if (held != null) {
            returns.queue(player, held);
        }
    }

    // --- Limits and watchdog ---

    /** Ends {@code session} if the players broke a limit (DESIGN.md 3.5 #15). Movement never matters, only positions. */
    public void checkLimits(TradeSession session) {
        Player a = player(session.a());
        Player b = player(session.b());
        if (session.isEnded() || a == null || b == null) {
            return;
        }
        LimitsCheck.Failure failure = LimitsCheck.check(settings().limits(), position(a), position(b),
                a.hasPermission(BYPASS_DISTANCE) || b.hasPermission(BYPASS_DISTANCE),
                a.hasPermission(BYPASS_WORLD), b.hasPermission(BYPASS_WORLD));
        if (failure == null) {
            return;
        }
        switch (failure) {
            case TOO_FAR -> abort(session, EndReason.TOO_FAR, null, Set.of());
            case OTHER_WORLD -> abort(session, EndReason.WORLD, b.getUniqueId(), Set.of());
            case DISABLED_WORLD -> abort(session, EndReason.WORLD,
                    settings().limits().isWorldDisabled(a.getWorld().getName()) ? a.getUniqueId() : b.getUniqueId(),
                    Set.of());
        }
    }

    /** (Re)starts the limits timer and the watchdog. Called on enable and after a reload. */
    public void startTasks() {
        stopTasks();
        int ticks = settings().limits().distanceCheckTicks();
        limitsTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> registry.sessions().forEach(this::checkLimits), ticks, ticks);
        watchdogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::watchdog, 20L, 20L);
    }

    private void stopTasks() {
        if (limitsTask != null) {
            limitsTask.cancel();
            limitsTask = null;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
    }

    /** DESIGN.md 3.5 #21: both traders online and looking at their window, and no stale registry entries. */
    void watchdog() {
        for (UUID stale : registry.removeEnded()) {
            plugin.getLogger().warning("Removed a leftover trade entry for " + stale + "; the trade had already ended.");
        }
        for (TradeSession session : registry.sessions()) {
            for (Side side : List.of(session.a(), session.b())) {
                Player player = player(side);
                if (player == null) {
                    abort(session, EndReason.QUIT, side.playerId(), Set.of());
                    break;
                }
                boolean away = side.view() == ViewState.INPUT_CHAT || side.view() == ViewState.INPUT_FORM
                        || side.view() == ViewState.REOPENING;
                if (!away && !isOwnView(player, session)) {
                    abort(session, EndReason.LOST_VIEW, null, Set.of());
                    break;
                }
            }
        }
    }

    // --- Helpers ---

    /** Whether {@code player} is looking at this trade's window or their own editor for it. */
    public boolean isOwnView(Player player, TradeSession session) {
        Inventory top = player.getOpenInventory().getTopInventory();
        TradeHolder trade = TradeHolder.of(top);
        if (trade != null) {
            return trade.session() == session && trade.side().playerId().equals(player.getUniqueId());
        }
        EditorHolder editor = EditorHolder.of(top);
        return editor != null && editor.session() == session && editor.side().playerId().equals(player.getUniqueId());
    }

    public void render(TradeSession session) {
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            if (player == null) {
                continue;
            }
            Inventory top = player.getOpenInventory().getTopInventory();
            TradeHolder trade = TradeHolder.of(top);
            if (trade != null && trade.session() == session) {
                view.render(trade);
                continue;
            }
            EditorHolder editor = EditorHolder.of(top);
            if (editor != null && editor.session() == session) {
                view.renderEditor(editor, editorMax(player, editor.kind()));
            }
        }
    }

    /** The 36 storage slots, the only ones items are ever added to. */
    private static ItemStack[] storage(PlayerInventory inventory) {
        ItemStack[] slots = new ItemStack[PlayerStorage.SLOTS];
        for (int slot = 0; slot < PlayerStorage.SLOTS; slot++) {
            slots[slot] = inventory.getItem(slot);
        }
        return slots;
    }

    private static Player player(Side side) {
        return Bukkit.getPlayer(side.playerId());
    }

    private static Position position(Player player) {
        Location at = player.getLocation();
        return new Position(at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
    }

    private void playBoth(TradeSession session, SoundSetting sound) {
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            if (player != null) {
                play(player, sound);
            }
        }
    }

    private static void play(Player player, SoundSetting sound) {
        if (sound.enabled()) {
            player.playSound(player.getLocation(), sound.sound().asString(), sound.volume(), sound.pitch());
        }
    }
}
