package com.mrstudios.trades.history;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the trade log lives (DESIGN.md 6.2). Every method blocks, so it's only called on the
 * {@code MR-Trades-DB} thread.
 */
public interface Database extends AutoCloseable {

    /** A player found by name in the log: their UUID and the name as last recorded. */
    record NameMatch(UUID id, String name) {
    }

    /** Creates or upgrades the tables. */
    void migrate() throws SQLException;

    /** Adds a row. A row whose id is already stored is skipped (backlog imports may repeat). */
    void insert(TradeRecord record) throws SQLException;

    /** {@code player}'s trades, newest first. */
    List<TradeRecord> page(UUID player, int offset, int limit) throws SQLException;

    int count(UUID player) throws SQLException;

    /** The most recent trader recorded with this name, ignoring case. */
    Optional<NameMatch> findName(String name) throws SQLException;

    /** Deletes trades completed before {@code millis}; returns how many. */
    int deleteBefore(long millis) throws SQLException;

    /** For logs and {@code /mrtrades version}, e.g. {@code SQLite (history.db)}. */
    String describe();

    @Override
    void close();
}
