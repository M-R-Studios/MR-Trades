package com.mrstudios.trades.input;

import com.mrstudios.trades.gui.EditorHolder;
import com.mrstudios.trades.trade.Side;
import com.mrstudios.trades.trade.TradeSession;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is typing an amount right now, in chat or in a Bedrock form (DESIGN.md 4.2, 3.5 #16). Read
 * from the async chat thread, so it's a concurrent map; everything else happens on the main
 * thread. Each wait has its own token, so a late answer (after a timeout, a cancel or the end of
 * the trade) can never apply to a newer wait.
 */
public final class ChatInput {

    /** Something to cancel when the wait ends early (the timeout task). */
    @FunctionalInterface
    public interface Timeout {
        void cancel();
    }

    /**
     * One wait.
     *
     * @param form a Bedrock form, not chat
     */
    public record Pending(TradeSession session, Side side, EditorHolder.Kind kind, UUID token, boolean form,
            Timeout timeout) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public void start(UUID player, Pending wait) {
        Pending old = pending.put(player, wait);
        if (old != null && old.timeout() != null) {
            old.timeout().cancel();
        }
    }

    /** A form couldn't be sent: the same wait (same token and timeout) continues in chat. */
    public void switchToChat(UUID player, UUID token) {
        pending.computeIfPresent(player, (id, wait) -> wait.token().equals(token)
                ? new Pending(wait.session(), wait.side(), wait.kind(), wait.token(), false, wait.timeout())
                : wait);
    }

    /** The wait for {@code player}, or null. Safe from any thread. */
    public Pending get(UUID player) {
        return pending.get(player);
    }

    /** Whether {@code player}'s chat messages are amounts right now. Safe from any thread. */
    public boolean isTypingInChat(UUID player) {
        Pending wait = pending.get(player);
        return wait != null && !wait.form();
    }

    /**
     * Ends {@code player}'s wait if it's still the one with {@code token}.
     *
     * @return the wait that ended, or null if it had already ended or been replaced
     */
    public Pending finish(UUID player, UUID token) {
        Pending wait = pending.get(player);
        if (wait == null || !wait.token().equals(token) || !pending.remove(player, wait)) {
            return null;
        }
        if (wait.timeout() != null) {
            wait.timeout().cancel();
        }
        return wait;
    }

    /** Drops {@code player}'s wait, whatever it is (the trade ended). */
    public void clear(UUID player) {
        Pending wait = pending.remove(player);
        if (wait != null && wait.timeout() != null) {
            wait.timeout().cancel();
        }
    }
}
