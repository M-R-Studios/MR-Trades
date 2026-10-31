package com.mrstudios.trades.returns;

import com.mrstudios.trades.MRTrades;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Item records kept on the player (DESIGN.md 3.6), in their own {@code PersistentDataContainer}.
 * The player's inventory and these records are written by the same player-data save, so after a
 * crash an offered item is either still in the inventory or in the escrow record, never both and
 * never neither.
 *
 * <ul>
 *   <li>{@code mrtrades:escrow} + {@code mrtrades:escrow_session}: the items the player has in a
 *       live trade's offer right now.</li>
 *   <li>{@code mrtrades:returns}: items owed to the player that didn't fit in their inventory.</li>
 * </ul>
 *
 * Items are stored with {@code ItemStack.serializeItemsAsBytes}, which includes the data version,
 * so stored items upgrade across Minecraft versions.
 */
public final class EscrowStore {

    /** A leftover escrow record: the trade it belonged to and its items. */
    public record Escrow(UUID sessionId, List<ItemStack> items) {
    }

    private final NamespacedKey escrowKey;
    private final NamespacedKey escrowSessionKey;
    private final NamespacedKey returnsKey;
    private final Logger logger;

    public EscrowStore(Plugin plugin) {
        this.escrowKey = new NamespacedKey(MRTrades.NAMESPACE, "escrow");
        this.escrowSessionKey = new NamespacedKey(MRTrades.NAMESPACE, "escrow_session");
        this.returnsKey = new NamespacedKey(MRTrades.NAMESPACE, "returns");
        this.logger = plugin.getLogger();
    }

    /** Records {@code items} as {@code player}'s current offer in trade {@code sessionId}. Empty clears it. */
    public void writeEscrow(Player player, UUID sessionId, Collection<ItemStack> items) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        List<ItemStack> real = nonEmpty(items);
        if (real.isEmpty()) {
            clearEscrow(player);
            return;
        }
        pdc.set(escrowKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(real));
        pdc.set(escrowSessionKey, PersistentDataType.STRING, sessionId.toString());
    }

    public void clearEscrow(Player player) {
        player.getPersistentDataContainer().remove(escrowKey);
        player.getPersistentDataContainer().remove(escrowSessionKey);
    }

    /** The escrow record on {@code player}, or null if there is none. */
    public Escrow readEscrow(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] bytes = pdc.get(escrowKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return null;
        }
        UUID session = null;
        String raw = pdc.get(escrowSessionKey, PersistentDataType.STRING);
        try {
            session = raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // An unreadable id can't match a live trade, which is what matters.
        }
        return new Escrow(session, decode(player, bytes, "escrow"));
    }

    /** The items waiting in {@code player}'s return queue. */
    public List<ItemStack> readQueue(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(returnsKey, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? new ArrayList<>() : decode(player, bytes, "return queue");
    }

    /** Replaces {@code player}'s return queue with {@code items}. Empty removes it. */
    public void writeQueue(Player player, Collection<ItemStack> items) {
        List<ItemStack> real = nonEmpty(items);
        if (real.isEmpty()) {
            player.getPersistentDataContainer().remove(returnsKey);
        } else {
            player.getPersistentDataContainer().set(returnsKey, PersistentDataType.BYTE_ARRAY,
                    ItemStack.serializeItemsAsBytes(real));
        }
    }

    /** Adds {@code items} to the end of {@code player}'s return queue. */
    public void appendQueue(Player player, Collection<ItemStack> items) {
        List<ItemStack> queue = readQueue(player);
        queue.addAll(nonEmpty(items));
        writeQueue(player, queue);
    }

    private List<ItemStack> decode(Player player, byte[] bytes, String what) {
        try {
            return new ArrayList<>(nonEmpty(Arrays.asList(ItemStack.deserializeItemsFromBytes(bytes))));
        } catch (RuntimeException e) {
            // Never silently drop a record: keep the bytes in place and say so loudly.
            logger.severe("Couldn't read the " + what + " stored on " + player.getName() + " (" + player.getUniqueId()
                    + "); the record was left untouched: " + e);
            throw new IllegalStateException("Unreadable " + what + " on " + player.getName(), e);
        }
    }

    private static List<ItemStack> nonEmpty(Collection<ItemStack> items) {
        return items.stream()
                .filter(Objects::nonNull)
                .filter(i -> !i.getType().isAir() && i.getAmount() > 0)
                .map(ItemStack::clone)
                .toList();
    }
}
