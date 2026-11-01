package com.mrstudios.trades.history;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL shared by SQLite and MySQL. Subclasses give the connection, the table definitions and
 * the insert-ignore syntax.
 */
abstract class JdbcDatabase implements Database {

    private static final String COLUMNS = "id, completed_at, server, world, x, y, z, a_uuid, a_name, b_uuid, b_name,"
            + " a_items, b_items, a_summary, b_summary, a_money, b_money, a_xp, b_xp, economy_error, data_version";

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    protected final String prefix;
    protected final String trades;
    protected final String schema;

    JdbcDatabase(String prefix) {
        this.prefix = prefix;
        this.trades = quote(prefix + "trades");
        this.schema = quote(prefix + "schema");
    }

    /** Runs {@code work} with a connection. */
    abstract <T> T with(SqlWork<T> work) throws SQLException;

    abstract String quote(String identifier);

    /** {@code INSERT OR IGNORE INTO} / {@code INSERT IGNORE INTO}. */
    abstract String insertIgnore();

    abstract String createSchemaTable();

    /** The schema migrations in order; entry {@code i} upgrades to version {@code i + 1}. */
    abstract List<List<String>> migrations();

    @Override
    public void migrate() throws SQLException {
        with(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(createSchemaTable());
            }
            int current = 0;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT MAX(version) FROM " + schema)) {
                if (rows.next()) {
                    current = rows.getInt(1);
                }
            }
            List<List<String>> migrations = migrations();
            if (current > migrations.size()) {
                throw new SQLException("The trade log has schema version " + current + ", newer than this version of"
                        + " MR-Trades knows (" + migrations.size() + "). Update MR-Trades.");
            }
            for (int version = current + 1; version <= migrations.size(); version++) {
                boolean auto = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : migrations.get(version - 1)) {
                        statement.execute(sql);
                    }
                    statement.execute("INSERT INTO " + schema + " (version) VALUES (" + version + ")");
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(auto);
                }
            }
            return null;
        });
    }

    @Override
    public void insert(TradeRecord r) throws SQLException {
        String sql = insertIgnore() + " " + trades + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        with(connection -> {
            try (PreparedStatement s = connection.prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, r.id().toString());
                s.setLong(i++, r.completedAt());
                s.setString(i++, r.server());
                s.setString(i++, r.world());
                s.setInt(i++, r.x());
                s.setInt(i++, r.y());
                s.setInt(i++, r.z());
                s.setString(i++, r.aId().toString());
                s.setString(i++, r.aName());
                s.setString(i++, r.bId().toString());
                s.setString(i++, r.bName());
                s.setBytes(i++, r.aItems());
                s.setBytes(i++, r.bItems());
                s.setString(i++, r.aSummary());
                s.setString(i++, r.bSummary());
                s.setString(i++, r.aMoney().toPlainString());
                s.setString(i++, r.bMoney().toPlainString());
                s.setInt(i++, r.aXp());
                s.setInt(i++, r.bXp());
                s.setInt(i++, r.economyError() ? 1 : 0);
                s.setInt(i, r.dataVersion());
                s.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<TradeRecord> page(UUID player, int offset, int limit) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + trades + " WHERE a_uuid = ? OR b_uuid = ?"
                + " ORDER BY completed_at DESC, id LIMIT ? OFFSET ?";
        return with(connection -> {
            try (PreparedStatement s = connection.prepareStatement(sql)) {
                s.setString(1, player.toString());
                s.setString(2, player.toString());
                s.setInt(3, limit);
                s.setInt(4, offset);
                List<TradeRecord> records = new ArrayList<>();
                try (ResultSet rows = s.executeQuery()) {
                    while (rows.next()) {
                        records.add(read(rows));
                    }
                }
                return records;
            }
        });
    }

    private static TradeRecord read(ResultSet rows) throws SQLException {
        return new TradeRecord(UUID.fromString(rows.getString("id")), rows.getLong("completed_at"),
                rows.getString("server"), rows.getString("world"), rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                UUID.fromString(rows.getString("a_uuid")), rows.getString("a_name"),
                UUID.fromString(rows.getString("b_uuid")), rows.getString("b_name"),
                bytes(rows.getBytes("a_items")), bytes(rows.getBytes("b_items")),
                rows.getString("a_summary"), rows.getString("b_summary"),
                money(rows.getString("a_money")), money(rows.getString("b_money")),
                rows.getInt("a_xp"), rows.getInt("b_xp"), rows.getInt("economy_error") != 0, rows.getInt("data_version"));
    }

    private static byte[] bytes(byte[] value) {
        return value == null ? new byte[0] : value;
    }

    /** MySQL returns {@code 100.0000}; shown amounts shouldn't carry the column's scale. */
    private static BigDecimal money(String value) {
        BigDecimal amount = new BigDecimal(value).stripTrailingZeros();
        return amount.scale() < 0 ? amount.setScale(0) : amount;
    }

    @Override
    public int count(UUID player) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + trades + " WHERE a_uuid = ? OR b_uuid = ?";
        return with(connection -> {
            try (PreparedStatement s = connection.prepareStatement(sql)) {
                s.setString(1, player.toString());
                s.setString(2, player.toString());
                try (ResultSet rows = s.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public Optional<NameMatch> findName(String name) throws SQLException {
        String sql = "SELECT a_uuid, a_name, b_uuid, b_name FROM " + trades
                + " WHERE LOWER(a_name) = ? OR LOWER(b_name) = ? ORDER BY completed_at DESC LIMIT 1";
        String lower = name.toLowerCase(Locale.ROOT);
        return with(connection -> {
            try (PreparedStatement s = connection.prepareStatement(sql)) {
                s.setString(1, lower);
                s.setString(2, lower);
                try (ResultSet rows = s.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    boolean a = rows.getString("a_name").toLowerCase(Locale.ROOT).equals(lower);
                    return Optional.of(new NameMatch(UUID.fromString(rows.getString(a ? "a_uuid" : "b_uuid")),
                            rows.getString(a ? "a_name" : "b_name")));
                }
            }
        });
    }

    @Override
    public int deleteBefore(long millis) throws SQLException {
        return with(connection -> {
            try (PreparedStatement s = connection.prepareStatement("DELETE FROM " + trades + " WHERE completed_at < ?")) {
                s.setLong(1, millis);
                return s.executeUpdate();
            }
        });
    }
}
