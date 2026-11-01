package com.mrstudios.trades.listener;

import com.mrstudios.trades.input.ChatInput;
import com.mrstudios.trades.trade.TradeService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

/**
 * DESIGN.md 3.5 #16: while a player types an amount, their chat messages are cancelled (never
 * broadcast) and read on the main thread. Both the modern and the legacy chat event are cancelled,
 * so chat plugins or Discord bridges listening to either never see the amount.
 */
public final class ChatListener implements Listener {

    private final Plugin plugin;
    private final ChatInput input;
    private final TradeService trades;

    public ChatListener(Plugin plugin, ChatInput input, TradeService trades) {
        this.plugin = plugin;
        this.input = input;
        this.trades = trades;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!input.isTypingInChat(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> trades.chatAmount(player, text));
    }

    @SuppressWarnings("deprecation") // Cancelled too, for plugins that still listen to the legacy event.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (input.isTypingInChat(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
