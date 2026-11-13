package com.example.finance.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.finance.domain.Account;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Transfer;
import com.example.finance.storage.Database;
import com.example.finance.storage.JdbcAccountRepository;
import com.example.finance.storage.JdbcOperationRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CachedBalanceServiceTest {

    private static final String REDIS_IMAGE = "redis:8";
    private static final int REDIS_PORT = 6379;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);

    private static HikariDataSource dataSource;
    private static RedisClient redisClient;
    private static StatefulRedisConnection<String, String> redis;

    private final Instant at = Instant.parse("2026-11-13T12:00:00Z");

    private UUID userId;
    private JdbcAccountRepository accounts;
    private JdbcOperationRepository operations;
    private CachedBalanceService balances;

    @BeforeAll
    static void connect() {
        dataSource = Database.createDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Database.migrate(dataSource);
        redisClient = Redis.createClient(redisUrl(REDIS));
        redis = redisClient.connect();
    }

    @AfterAll
    static void disconnect() {
        redis.close();
        Redis.shutdown(redisClient);
        dataSource.close();
    }

    @BeforeEach
    void newUser() throws SQLException {
        userId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, userId + "@test");
            ps.setString(3, "!");
            ps.setString(4, "USER");
            ps.executeUpdate();
        }
        accounts = new JdbcAccountRepository(dataSource, userId);
        balances = new CachedBalanceService(accounts, redisClient);
        operations = new JdbcOperationRepository(dataSource, userId, balances::evict);
    }

    @AfterEach
    void closeService() {
        balances.close();
    }

    @Test
    void firstReadIsMissAndSecondReadIsHit() {
        var main = account("main");
        operations.saveOperation(income(main, "1000.00"));

        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("1000.00"));
        assertThat(balances.misses()).isEqualTo(1);
        assertThat(balances.hits()).isZero();

        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("1000.00"));
        assertThat(balances.misses()).isEqualTo(1);
        assertThat(balances.hits()).isEqualTo(1);

        String key = "balance:" + main.id();
        assertThat(redis.sync().get(key)).isEqualTo("1000.00");
        assertThat(redis.sync().ttl(key)).isBetween(1L, 60L);
    }

    @Test
    void readAfterExpenseReturnsNewBalance() {
        var main = account("main");
        operations.saveOperation(income(main, "1000.00"));
        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("1000.00"));

        operations.saveOperation(
                new Expense(UUID.randomUUID(), main.id(), new BigDecimal("250.50"), "Продукты", at, null));

        assertThat(redis.sync().exists("balance:" + main.id())).isZero();
        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("749.50"));
        assertThat(balances.misses()).isEqualTo(2);
    }

    @Test
    void readAfterTransferReturnsNewBalancesOfBothAccounts() {
        var main = account("main");
        var savings = account("savings");
        operations.saveOperation(income(main, "1000.00"));
        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("1000.00"));
        assertThat(balances.findBalance(savings.id())).contains(new BigDecimal("0.00"));

        operations.saveOperation(
                new Transfer(UUID.randomUUID(), main.id(), savings.id(), new BigDecimal("300.00"), at));

        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("700.00"));
        assertThat(balances.findBalance(savings.id())).contains(new BigDecimal("300.00"));
    }

    @Test
    void failedExpenseKeepsCachedBalance() {
        var main = account("main");
        operations.saveOperation(income(main, "50.00"));
        assertThat(balances.findBalance(main.id())).contains(new BigDecimal("50.00"));

        try {
            operations.saveOperation(
                    new Expense(UUID.randomUUID(), main.id(), new BigDecimal("100.00"), "Техника", at, null));
        } catch (RuntimeException expected) {
            // Недостаточно средств: транзакция откатилась, кэш трогать незачем.
        }

        assertThat(redis.sync().get("balance:" + main.id())).isEqualTo("50.00");
    }

    @Test
    void whenRedisIsUnavailableBalanceIsReadFromPostgresAndWarnIsLogged() {
        var main = account("main");
        var logger = (Logger) LoggerFactory.getLogger(CachedBalanceService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        RedisClient client = null;
        try (var ownRedis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT)) {
            ownRedis.start();
            client = Redis.createClient(redisUrl(ownRedis));
            try (var service = new CachedBalanceService(accounts, client)) {
                var ops = new JdbcOperationRepository(dataSource, userId, service::evict);
                ops.saveOperation(income(main, "1000.00"));
                assertThat(service.findBalance(main.id())).contains(new BigDecimal("1000.00"));
                assertThat(appender.list).isEmpty();

                ownRedis.stop();

                ops.saveOperation(
                        new Expense(UUID.randomUUID(), main.id(), new BigDecimal("400.00"), "Продукты", at, null));
                assertThat(service.findBalance(main.id())).contains(new BigDecimal("600.00"));
                assertThat(service.hits()).isZero();
                assertThat(service.misses()).isEqualTo(1);
            }
        } finally {
            logger.detachAppender(appender);
            if (client != null) {
                Redis.shutdown(client);
            }
        }

        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Redis недоступен")
                        .contains(main.id().toString()));
    }

    @Test
    void whenRedisWasNeverReachableBalanceIsReadFromPostgres() {
        var main = account("main");
        RedisClient client = Redis.createClient("redis://localhost:1");
        try (var service = new CachedBalanceService(accounts, client)) {
            var ops = new JdbcOperationRepository(dataSource, userId, service::evict);
            ops.saveOperation(income(main, "10.00"));

            assertThat(service.findBalance(main.id())).contains(new BigDecimal("10.00"));
        } finally {
            Redis.shutdown(client);
        }
    }

    private static String redisUrl(GenericContainer<?> container) {
        return "redis://" + container.getHost() + ":" + container.getMappedPort(REDIS_PORT);
    }

    private Account account(String name) {
        var account = new Account(UUID.randomUUID(), name, CurrencyCode.BYN);
        accounts.saveAccount(account);
        return account;
    }

    private Income income(Account account, String amount) {
        return new Income(UUID.randomUUID(), account.id(), new BigDecimal(amount), "Зарплата", at, null);
    }
}
