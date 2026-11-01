package com.mrstudios.trades.history;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** The trade log in a file in the plugin folder: one connection, write-ahead logging (DESIGN.md 6.2). */
public final class SqliteDatabase extends JdbcDatabase {

    private final File file;
    private final Connection connection;

    public SqliteDatabase(File file) throws SQLException {
        super("");
        this.file = file;
        try {
            // Paper ships the driver; load it by name in case the service lookup can't see it.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite driver isn't available on this server", e);
        }
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            throw new SQLException("Couldn't create " + folder);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    @Override
    synchronized <T> T with(SqlWork<T> work) throws SQLException {
        return work.run(connection);
    }

    @Override
    String quote(String identifier) {
        return '"' + identifier + '"';
    }

    @Override
    String insertIgnore() {
        return "INSERT OR IGNORE INTO";
    }

    @Override
    String createSchemaTable() {
        return "CREATE TABLE IF NOT EXISTS " + schema + " (version INTEGER NOT NULL)";
    }

    @Override
    List<List<String>> migrations() {
        return List.of(List.of(
                "CREATE TABLE IF NOT EXISTS " + trades + " ("
                        + "id TEXT NOT NULL PRIMARY KEY, completed_at INTEGER NOT NULL, server TEXT NOT NULL,"
                        + " world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                        + " a_uuid TEXT NOT NULL, a_name TEXT NOT NULL, b_uuid TEXT NOT NULL, b_name TEXT NOT NULL,"
                        + " a_items BLOB NOT NULL, b_items BLOB NOT NULL, a_summary TEXT NOT NULL, b_summary TEXT NOT NULL,"
                        + " a_money TEXT NOT NULL, b_money TEXT NOT NULL, a_xp INTEGER NOT NULL, b_xp INTEGER NOT NULL,"
                        + " economy_error INTEGER NOT NULL, data_version INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + quote("trades_a") + " ON " + trades + " (a_uuid, completed_at)",
                "CREATE INDEX IF NOT EXISTS " + quote("trades_b") + " ON " + trades + " (b_uuid, completed_at)",
                "CREATE INDEX IF NOT EXISTS " + quote("trades_time") + " ON " + trades + " (completed_at)"));
    }

    @Override
    public String describe() {
        return "SQLite (" + file.getName() + ")";
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Shutting down; nothing more to do with it.
        }
    }
}
