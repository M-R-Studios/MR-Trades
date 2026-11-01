package com.mrstudios.trades.history;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * One row of the trade log (DESIGN.md 6.1). Immutable: built on the main thread at completion
 * (items are serialized there, since ItemStacks aren't thread-safe) and written on another thread.
 *
 * @param aItems what A gave, {@code ItemStack.serializeItemsAsBytes}; empty when A gave no items
 * @param aMoney A's gross money offer, not the net amount that moved
 * @param economyError the payment's refund failed (DESIGN.md 3.5.1 step 4.3); no items moved
 */
public record TradeRecord(UUID id, long completedAt, String server, String world, int x, int y, int z,
        UUID aId, String aName, UUID bId, String bName, byte[] aItems, byte[] bItems,
        String aSummary, String bSummary, BigDecimal aMoney, BigDecimal bMoney, int aXp, int bXp,
        boolean economyError, int dataVersion) {

    /** A trade from one player's point of view: what they gave and what they got. */
    public record View(UUID partnerId, String partnerName, byte[] gaveItems, byte[] gotItems,
            String gaveSummary, String gotSummary, BigDecimal gaveMoney, BigDecimal gotMoney,
            int gaveXp, int gotXp) {
    }

    /** This trade from {@code player}'s point of view. */
    public View viewOf(UUID player) {
        if (player.equals(aId)) {
            return new View(bId, bName, aItems, bItems, aSummary, bSummary, aMoney, bMoney, aXp, bXp);
        }
        return new View(aId, aName, bItems, aItems, bSummary, aSummary, bMoney, aMoney, bXp, aXp);
    }

    /** Serializes items for a record. No items give an empty array. */
    public static byte[] serialize(List<ItemStack> items) {
        if (items.isEmpty()) {
            return new byte[0];
        }
        return ItemStack.serializeItemsAsBytes(items);
    }

    /** The items stored in {@code bytes}; throws if they can't be read. */
    public static List<ItemStack> deserialize(byte[] bytes) {
        if (bytes.length == 0) {
            return List.of();
        }
        return Arrays.asList(ItemStack.deserializeItemsFromBytes(bytes));
    }

    /**
     * Human-readable, for admins searching the database and for the history lore, e.g.
     * {@code 64x Diamond, 1x Diamond Sword "Excalibur"}. Similar stacks are added up.
     */
    public static String summarize(List<ItemStack> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                counts.merge(label(item), item.getAmount(), Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((label, amount) -> parts.add(amount + "x " + label));
        return String.join(", ", parts);
    }

    private static String label(ItemStack item) {
        StringBuilder name = new StringBuilder();
        for (String word : item.getType().getKey().getKey().split("_")) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            name.append(" \"").append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('"');
        }
        return name.toString();
    }

    // Records compare arrays by reference; rows are compared by content.
    @Override
    public boolean equals(Object o) {
        return o instanceof TradeRecord r && id.equals(r.id) && completedAt == r.completedAt
                && server.equals(r.server) && world.equals(r.world) && x == r.x && y == r.y && z == r.z
                && aId.equals(r.aId) && aName.equals(r.aName) && bId.equals(r.bId) && bName.equals(r.bName)
                && Arrays.equals(aItems, r.aItems) && Arrays.equals(bItems, r.bItems)
                && aSummary.equals(r.aSummary) && bSummary.equals(r.bSummary)
                && aMoney.compareTo(r.aMoney) == 0 && bMoney.compareTo(r.bMoney) == 0
                && aXp == r.aXp && bXp == r.bXp && economyError == r.economyError && dataVersion == r.dataVersion;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "TradeRecord[" + id + " " + aName + " <-> " + bName + " at " + completedAt + "]";
    }
}
