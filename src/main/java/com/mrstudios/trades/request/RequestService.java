package com.mrstudios.trades.request;

import com.mrstudios.trades.MRTrades;
import com.mrstudios.trades.config.Messages;
import com.mrstudios.trades.config.Settings;
import com.mrstudios.trades.config.Settings.SoundSetting;
import com.mrstudios.trades.request.RequestChecks.Denial;
import com.mrstudios.trades.request.RequestChecks.Party;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import com.mrstudios.trades.trade.TradeRegistry;
import com.mrstudios.trades.trade.TradeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Sending, answering, withdrawing and expiring trade requests (DESIGN.md 2.1, 3.8, 4.1). Every
 * answer is checked again against the current state of both players. Main thread only.
 */
public final class RequestService {

    public static final String PERMISSION_TRADE = "mrtrades.trade";
    public static final String BYPASS_COOLDOWN = "mrtrades.bypass.cooldown";
    public static final String BYPASS_DISTANCE = "mrtrades.bypass.distance";
    public static final String BYPASS_WORLD = "mrtrades.bypass.world";
    public static final String BYPASS_TOGGLE = "mrtrades.bypass.toggle";

    private final MRTrades plugin;
    private final RequestManager requests;
    private final CooldownTracker cooldowns;
    private final RequestToggle toggle;
    private final TradeRegistry trades;
    private TradeService tradeService;

    public RequestService(MRTrades plugin, RequestManager requests, CooldownTracker cooldowns, RequestToggle toggle,
            TradeRegistry trades) {
        this.plugin = plugin;
        this.requests = requests;
        this.cooldowns = cooldowns;
        this.toggle = toggle;
        this.trades = trades;
    }

    /** Set once the trade service exists (it's built after this service). */
    public void setTradeService(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    public RequestManager requests() {
        return requests;
    }

    private Messages messages() {
        return plugin.state().messages();
    }

    private Settings settings() {
        return plugin.state().settings();
    }

    /** {@code /trade <name>}. Requesting a player who already requested you accepts their request. */
    public void send(Player sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        // A hidden (vanished) player is treated exactly like an offline one.
        if (target == null || !sender.canSee(target)) {
            messages().send(sender, "target-offline", Placeholder.unparsed("player", targetName));
            return;
        }
        if (target.equals(sender)) {
            messages().send(sender, "target-self");
            return;
        }
        Optional<TradeRequest> theirs = requests.find(target.getUniqueId(), sender.getUniqueId());
        if (theirs.isPresent()) {
            accept(sender, theirs.get());
            return;
        }
        Denial denial = RequestChecks.checkSend(party(sender), party(target), settings().limits());
        if (denial != null) {
            send(sender, denial);
            return;
        }
        if (requests.find(sender.getUniqueId(), target.getUniqueId()).isPresent()) {
            messages().send(sender, "request-already-sent", Placeholder.unparsed("player", target.getName()));
            return;
        }
        long cooldownMs = settings().requests().cooldownSeconds() * 1000L;
        if (!sender.hasPermission(BYPASS_COOLDOWN) && cooldowns.remainingMs(sender.getUniqueId(), cooldownMs) > 0) {
            messages().send(sender, "cooldown", Placeholder.unparsed("remaining",
                    String.valueOf(cooldowns.remainingSeconds(sender.getUniqueId(), cooldownMs))));
            return;
        }
        long timeoutMs = settings().requests().timeoutSeconds() * 1000L;
        requests.send(sender.getUniqueId(), sender.getName(), target.getUniqueId(), target.getName(), timeoutMs);
        cooldowns.sent(sender.getUniqueId());

        messages().send(sender, "request-sent", Placeholder.unparsed("player", target.getName()));
        TagResolver name = Placeholder.unparsed("player", sender.getName());
        messages().send(target, "request-received", name);
        if (settings().requests().clickableButtons()) {
            // The typed commands are always in request-received, so clients without clickable
            // chat (Bedrock) can still answer.
            messages().send(target, "request-buttons", name,
                    Placeholder.styling("accept", ClickEvent.runCommand("/trade accept " + sender.getName())),
                    Placeholder.styling("deny", ClickEvent.runCommand("/trade deny " + sender.getName())));
        }
        play(target, settings().sounds().request());
    }

    /** {@code /trade accept [name]}. */
    public void accept(Player player, String fromName) {
        find(player, fromName).ifPresent(request -> accept(player, request));
    }

    private void accept(Player accepter, TradeRequest request) {
        Player requester = Bukkit.getPlayer(request.from());
        if (requester == null || !accepter.canSee(requester)) {
            requests.remove(request);
            messages().send(accepter, "target-offline", Placeholder.unparsed("player", request.fromName()));
            return;
        }
        Denial denial = RequestChecks.checkAccept(party(accepter), party(requester), settings().limits());
        if (denial != null) {
            // The request stays, so they can fix it (walk closer, change game mode) and accept again.
            send(accepter, denial);
            return;
        }
        // Both players are busy from now on: their other requests, both ways, are dropped.
        requests.removeAllOf(requester.getUniqueId());
        requests.removeAllOf(accepter.getUniqueId());
        tradeService.start(requester, accepter);
    }

    /** {@code /trade deny [name]}. */
    public void deny(Player player, String fromName) {
        find(player, fromName).ifPresent(request -> {
            requests.remove(request);
            messages().send(player, "request-denied-self", Placeholder.unparsed("player", request.fromName()));
            Player requester = Bukkit.getPlayer(request.from());
            if (requester != null) {
                messages().send(requester, "request-denied", Placeholder.unparsed("player", player.getName()));
            }
        });
    }

    /** {@code /trade cancel}: withdraws every request the player sent. */
    public void cancel(Player player) {
        List<TradeRequest> outgoing = requests.outgoing(player.getUniqueId());
        if (outgoing.isEmpty()) {
            messages().send(player, "no-requests");
            return;
        }
        outgoing.forEach(requests::remove);
        messages().send(player, "request-cancelled");
    }

    /** {@code /trade toggle}. */
    public void toggle(Player player) {
        messages().send(player, toggle.toggle(player) ? "toggle-off" : "toggle-on");
    }

    /** Names of the players who sent {@code player} a live request, for tab completion. */
    public List<String> requesterNames(Player player) {
        List<String> names = new ArrayList<>();
        for (TradeRequest request : requests.incoming(player.getUniqueId())) {
            names.add(request.fromName());
        }
        return names;
    }

    /** Removes expired requests and tells both players. Runs every second. */
    public void expireTick() {
        for (TradeRequest request : requests.expire()) {
            Player sender = Bukkit.getPlayer(request.from());
            if (sender != null) {
                messages().send(sender, "request-expired-sender", Placeholder.unparsed("player", request.toName()));
            }
            Player target = Bukkit.getPlayer(request.to());
            if (target != null) {
                messages().send(target, "request-expired-target", Placeholder.unparsed("player", request.fromName()));
            }
        }
    }

    /** A player left: every request they sent or received is dropped. */
    public void quit(Player player) {
        requests.removeAllOf(player.getUniqueId());
    }

    /** The request {@code player} answers: from {@code fromName}, or the latest one when null. */
    private Optional<TradeRequest> find(Player player, String fromName) {
        if (fromName == null) {
            Optional<TradeRequest> latest = requests.latestTo(player.getUniqueId());
            if (latest.isEmpty()) {
                messages().send(player, "no-requests");
            }
            return latest;
        }
        Optional<TradeRequest> request = requests.incoming(player.getUniqueId()).stream()
                .filter(r -> r.fromName().equalsIgnoreCase(fromName))
                .findFirst();
        if (request.isEmpty()) {
            messages().send(player, "no-request", Placeholder.unparsed("player", fromName));
        }
        return request;
    }

    private Party party(Player player) {
        Location at = player.getLocation();
        return new Party(player.getUniqueId(), player.getName(),
                new Position(at.getWorld().getName(), at.getX(), at.getY(), at.getZ()),
                player.getGameMode(),
                player.hasPermission(PERMISSION_TRADE),
                player.hasPermission(BYPASS_DISTANCE),
                player.hasPermission(BYPASS_WORLD),
                player.hasPermission(BYPASS_TOGGLE),
                toggle.isOff(player),
                trades.isTrading(player.getUniqueId()));
    }

    private void send(Player player, Denial denial) {
        List<TagResolver> placeholders = new ArrayList<>();
        for (Map.Entry<String, String> entry : denial.placeholders().entrySet()) {
            placeholders.add(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        messages().send(player, denial.messageKey(), placeholders.toArray(TagResolver[]::new));
    }

    private static void play(Player player, SoundSetting sound) {
        if (sound.enabled()) {
            player.playSound(player.getLocation(), sound.sound().asString(), sound.volume(), sound.pitch());
        }
    }
}
