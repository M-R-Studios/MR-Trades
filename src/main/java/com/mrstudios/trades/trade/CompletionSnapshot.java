package com.mrstudios.trades.trade;

import com.mrstudios.trades.config.Settings.Limits;
import com.mrstudios.trades.hook.EconomyPort;
import com.mrstudios.trades.rules.LimitsCheck.Position;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.GameMode;

/**
 * Everything {@link CompletionValidator} needs, read from the server at the moment of completion.
 * Plain values only, so the validator needs no running server.
 *
 * @param version the session's current version
 * @param bypassDistance either player has {@code mrtrades.bypass.distance}
 * @param economy null when no economy is installed
 * @param inventoryMaxStack the receivers' inventory stack limit
 */
public record CompletionSnapshot(long version, Trader a, Trader b, Limits limits, boolean bypassDistance,
        EconomyPort economy, int inventoryMaxStack) {

    public CompletionSnapshot {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        Objects.requireNonNull(limits);
    }

    /**
     * One trader as they are right now.
     *
     * @param online still on the server
     * @param dead dead and not yet respawned
     * @param bypassWorld has {@code mrtrades.bypass.world}
     * @param position null when offline
     * @param confirmedAtVersion the version they confirmed, or -1
     * @param level current XP level
     * @param storage their storage slots (null = empty), where incoming items go
     * @param offeredItems the items they give
     * @param offeredMoney the money they give
     * @param offeredXp the levels they give
     */
    public record Trader(UUID id, boolean online, boolean dead, GameMode gameMode, boolean bypassWorld,
            Position position, long confirmedAtVersion, int level, List<StackView> storage,
            List<StackView> offeredItems, BigDecimal offeredMoney, int offeredXp) {

        public Trader {
            Objects.requireNonNull(id);
            // Empty slots are null, which List.copyOf doesn't allow.
            storage = Collections.unmodifiableList(new ArrayList<>(storage));
            offeredItems = List.copyOf(offeredItems);
            offeredMoney = offeredMoney == null ? BigDecimal.ZERO : offeredMoney;
        }
    }
}
