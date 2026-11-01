package com.mrstudios.trades.history;

import com.mrstudios.trades.config.Settings.Mysql;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** The trade log in MySQL or MariaDB through a HikariCP pool (DESIGN.md 6.2). */
public final class MysqlDatabase extends JdbcDatabase {

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    private final Mysql settings;
    private final HikariDataSource pool;

    public MysqlDatabase(Mysql settings) throws SQLException {
        super(settings.tablePrefix());
        this.settings = settings;
        HikariConfig config = new HikariConfig();
        config.setPoolName("MR-Trades");
        config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(5000);
        try {
            // Paper ships the driver; name it so the pool doesn't rely on the service lookup.
            Class.forName(DRIVER);
            config.setDriverClassName(DRIVER);
        } catch (ClassNotFoundException e) {
            // Left to DriverManager.
        }
        settings.properties().forEach(config::addDataSourceProperty);
        try {
            pool = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new SQLException("Couldn't connect to MySQL at " + settings.host() + ":" + settings.port() + ": "
                    + e.getMessage(), e);
        }
    }

    @Override
    <T> T with(SqlWork<T> work) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            return work.run(connection);
        }
    }

    @Override
    String quote(String identifier) {
        return '`' + identifier + '`';
    }

    @Override
    String insertIgnore() {
        return "INSERT IGNORE INTO";
    }

    @Override
    String createSchemaTable() {
        return "CREATE TABLE IF NOT EXISTS " + schema + " (version INT NOT NULL)";
    }

    @Override
    List<List<String>> migrations() {
        return List.of(List.of(
                "CREATE TABLE IF NOT EXISTS " + trades + " ("
                        + "id CHAR(36) NOT NULL PRIMARY KEY, completed_at BIGINT NOT NULL, server VARCHAR(64) NOT NULL,"
                        + " world VARCHAR(255) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,"
                        + " a_uuid CHAR(36) NOT NULL, a_name VARCHAR(32) NOT NULL,"
                        + " b_uuid CHAR(36) NOT NULL, b_name VARCHAR(32) NOT NULL,"
                        + " a_items MEDIUMBLOB NOT NULL, b_items MEDIUMBLOB NOT NULL,"
                        + " a_summary TEXT NOT NULL, b_summary TEXT NOT NULL,"
                        + " a_money DECIMAL(20,4) NOT NULL, b_money DECIMAL(20,4) NOT NULL,"
                        + " a_xp INT NOT NULL, b_xp INT NOT NULL, economy_error TINYINT NOT NULL, data_version INT NOT NULL,"
                        + " INDEX " + quote(prefix + "trades_a") + " (a_uuid, completed_at),"
                        + " INDEX " + quote(prefix + "trades_b") + " (b_uuid, completed_at),"
                        + " INDEX " + quote(prefix + "trades_time") + " (completed_at)"
                        + ") DEFAULT CHARSET=utf8mb4"));
    }

    @Override
    public String describe() {
        return "MySQL (" + settings.host() + ":" + settings.port() + "/" + settings.database() + ")";
    }

    @Override
    public void close() {
        pool.close();
    }
}
