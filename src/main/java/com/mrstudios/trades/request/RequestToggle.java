package com.mrstudios.trades.request;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * {@code /trade toggle}: whether a player turned trade requests off. Stored on the player
 * ({@code mrtrades:requests_off}), so it survives restarts and needs no database.
 */
public final class RequestToggle {

    private final NamespacedKey key;

    public RequestToggle(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "requests_off");
    }

    public boolean isOff(Player player) {
        return player.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** @return true if requests are now off */
    public boolean toggle(Player player) {
        if (isOff(player)) {
            player.getPersistentDataContainer().remove(key);
            return false;
        }
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        return true;
    }
}
