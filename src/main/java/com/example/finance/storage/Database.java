package com.example.finance.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class Database {

    /** Пользователь из миграции V4, от имени которого работает консоль. */
    public static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static final int MAXIMUM_POOL_SIZE = 10;
    static final long CONNECTION_TIMEOUT_MS = 3000;

    private Database() {}

    public static HikariDataSource createDataSource(String url, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setPoolName("finance");
        // Драйвер склеивает пачку однотипных INSERT в многострочные, это ускоряет insertAll.
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        return new HikariDataSource(config);
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }
}
