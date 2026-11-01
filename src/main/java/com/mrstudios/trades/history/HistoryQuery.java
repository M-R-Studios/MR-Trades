package com.mrstudios.trades.history;

import java.util.List;
import java.util.UUID;

/**
 * One page of a player's history (DESIGN.md 6.5).
 *
 * @param page 1-based; past the last page means the last page
 */
public record HistoryQuery(UUID player, String name, int page) {

    /** Entries per page: the top five rows of the window. */
    public static final int PAGE_SIZE = 45;

    public HistoryQuery {
        page = Math.max(1, page);
    }

    /** What came back: the page actually shown (clamped) and its rows, newest first. */
    public record Result(HistoryQuery query, List<TradeRecord> rows, int total) {

        public Result {
            rows = List.copyOf(rows);
        }

        public int pages() {
            return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        }

        public boolean hasPrevious() {
            return query.page() > 1;
        }

        public boolean hasNext() {
            return query.page() < pages();
        }
    }
}
