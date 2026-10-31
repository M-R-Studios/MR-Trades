package com.mrstudios.trades.returns;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Settings.WhenFull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Gives items back to their owner without ever deleting one (DESIGN.md 3.6): into the inventory
 * first; what doesn't fit goes to the return queue ({@code /trade claim}) or, with
 * {@code returns.when-full: DROP}, on the ground at the owner's feet, only pickable by them.
 */
public final class ReturnService {

    /** What a queue delivery did, counted in stacks. */
    public record Delivery(int given, int left) {
    }

    private final MRTrades plugin;
    private final EscrowStore store;

    public ReturnService(MRTrades plugin, EscrowStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public EscrowStore store() {
        return store;
    }

    /**
     * Returns {@code items} to {@code owner}, who must be online. Each item is handled on its own,
     * and a failure on one step only moves that item to the next safe place (inventory, then the
     * queue or the ground, then the other of those two). An item is never deleted.
     *
     * @param dead the owner is dying or dead: everything goes to the return queue, because items
     *     added to the inventory now could be lost with the death drops
     */
    public void giveBack(Player owner, Collection<ItemStack> items, boolean dead) {
        List<ItemStack> leftover = new ArrayList<>();
        for (ItemStack item : items) {
            if (!isReal(item)) {
                continue;
            }
            if (dead || isDying(owner)) {
                leftover.add(item);
                continue;
            }
            try {
                leftover.addAll(addToInventory(owner, item));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't put " + item + " into " + owner.getName()
                        + "'s inventory; keeping it in their return queue", e);
                leftover.add(item);
            }
        }
        if (leftover.isEmpty()) {
            return;
        }
        boolean drop = !dead && !isDying(owner)
                && plugin.state().settings().returns().whenFull() == WhenFull.DROP;
        List<ItemStack> queued = new ArrayList<>();
        int dropped = 0;
        for (ItemStack item : leftover) {
            if (drop && dropAtFeet(owner, item)) {
                dropped++;
            } else {
                queued.add(item);
            }
        }
        if (!queued.isEmpty()) {
            try {
                store.appendQueue(owner, queued);
                plugin.state().messages().send(owner, "returns-queued", count(queued.size()));
            } catch (RuntimeException e) {
                // Last resort: on the ground is still better than gone.
                plugin.getLogger().log(Level.SEVERE, "Couldn't write " + owner.getName()
                        + "'s return queue; dropping " + queued.size() + " stack(s) at their feet instead", e);
                queued.forEach(item -> owner.getWorld().dropItem(owner.getLocation(), item));
                dropped += queued.size();
            }
        }
        if (dropped > 0) {
            plugin.state().messages().send(owner, "returns-dropped", count(dropped));
        }
    }

    /** Drops {@code item} at the owner's feet, only pickable by them. False if nothing was dropped. */
    private boolean dropAtFeet(Player owner, ItemStack item) {
        Item entity;
        try {
            entity = owner.getWorld().dropItem(owner.getLocation(), item);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't drop " + item + " for " + owner.getName()
                    + "; queueing it instead", e);
            return false;
        }
        try {
            entity.setOwner(owner.getUniqueId());
        } catch (RuntimeException e) {
            // The item exists on the ground exactly once; only the pickup protection is missing.
            plugin.getLogger().log(Level.WARNING, "Dropped " + item + " for " + owner.getName()
                    + " but couldn't make it theirs only", e);
        }
        return true;
    }

    /** Puts {@code items} straight into the return queue (e.g. a move that unexpectedly didn't fit). */
    public void queue(Player owner, Collection<ItemStack> items) {
        store.appendQueue(owner, items);
    }

    /** Gives {@code player} as much of their return queue as fits; the rest stays queued. */
    public Delivery deliverQueue(Player player) {
        if (isDying(player)) {
            return new Delivery(0, store.readQueue(player).size());
        }
        List<ItemStack> queue = store.readQueue(player);
        if (queue.isEmpty()) {
            return new Delivery(0, 0);
        }
        List<ItemStack> left = new ArrayList<>();
        int given = 0;
        for (ItemStack item : queue) {
            List<ItemStack> rest = addToInventory(player, item);
            if (rest.isEmpty()) {
                given++;
            }
            left.addAll(rest);
        }
        store.writeQueue(player, left);
        return new Delivery(given, left.size());
    }

    /** Recovers a leftover escrow record (from a crash) into the return queue, then delivers it. */
    public Delivery recoverAndDeliver(Player player, Predicate<UUID> sessionIsLive) {
        EscrowStore.Escrow escrow = store.readEscrow(player);
        if (escrow != null && (escrow.sessionId() == null || !sessionIsLive.test(escrow.sessionId()))) {
            store.appendQueue(player, escrow.items());
            store.clearEscrow(player);
            plugin.getLogger().info("Recovered " + escrow.items().size() + " offered item stack(s) of "
                    + player.getName() + " from an unfinished trade into their return queue.");
        }
        return deliverQueue(player);
    }

    private static List<ItemStack> addToInventory(Player player, ItemStack item) {
        ItemStack rest = PlayerStorage.add(player.getInventory(), item.clone());
        return rest == null ? List.of() : List.of(rest);
    }

    /**
     * Dying or dead, judged by health. Not {@code Player#isDead()}: that is briefly true while a
     * player changes world, which would send their items to the queue for no reason.
     */
    private static boolean isDying(Player player) {
        return player.getHealth() <= 0;
    }

    private static boolean isReal(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static TagResolver count(int stacks) {
        return Placeholder.unparsed("count", String.valueOf(stacks));
    }
}
