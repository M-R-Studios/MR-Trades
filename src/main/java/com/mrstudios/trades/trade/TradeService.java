package com.mrstudios.trades.trade;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.Settings.SoundSetting;
import com.mrstudios.trades.gui.DisplayItems;
import com.mrstudios.trades.gui.TradeHolder;
import com.mrstudios.trades.gui.TradeView;
import com.mrstudios.trades.returns.PlayerStorage;
import com.mrstudios.trades.returns.ReturnService;
import com.mrstudios.trades.rules.LimitsCheck;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import com.mrstudios.trades.trade.TradeSession.ChangeResult;
import com.mrstudios.trades.trade.TradeSession.ConfirmResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
    private BukkitTask limitsTask;
    private BukkitTask watchdogTask;
    private boolean shuttingDown;

    public TradeService(MRTrades plugin, TradeRegistry registry, ReturnService returns, TradeView view,
            DisplayItems display) {
        this.plugin = plugin;
        this.registry = registry;
        this.returns = returns;
        this.view = view;
        this.display = display;
    }

    public TradeRegistry registry() {
        return registry;
    }

    public DisplayItems display() {
        return display;
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
            player.openInventory(view.create(session, side).getInventory());
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
        // The countdown and completion arrive in step 5: with both confirmed the trade waits here.
        render(session);
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

    public boolean isOwnView(Player player, TradeSession session) {
        TradeHolder trade = TradeHolder.of(player.getOpenInventory().getTopInventory());
        return trade != null && trade.session() == session && trade.side().playerId().equals(player.getUniqueId());
    }

    public void render(TradeSession session) {
        for (Side side : List.of(session.a(), session.b())) {
            Player player = player(side);
            if (player != null) {
                TradeHolder trade = TradeHolder.of(player.getOpenInventory().getTopInventory());
                if (trade != null && trade.session() == session) {
                    view.render(trade);
                }
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
