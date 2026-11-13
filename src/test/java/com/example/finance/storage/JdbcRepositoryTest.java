package com.example.finance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finance.domain.Account;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.InsufficientFundsException;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(JdbcRepositoryTest.class);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    private static HikariDataSource dataSource;

    private final Instant at = Instant.parse("2026-10-31T12:00:00Z");

    private JdbcAccountRepository accounts;
    private JdbcOperationRepository operations;

    @BeforeAll
    static void migrate() {
        dataSource = Database.createDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Database.migrate(dataSource);
    }

    @AfterAll
    static void closeDataSource() {
        dataSource.close();
    }

    /** Каждый тест работает от имени своего пользователя и не видит данные других тестов. */
    @BeforeEach
    void newUser() throws SQLException {
        UUID userId = UUID.randomUUID();
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
        operations = new JdbcOperationRepository(dataSource, userId);
    }

    @Test
    void savedAccountsAndOperationsOfAllKindsAreReadBackEqual() {
        var main = account("main", CurrencyCode.BYN);
        var savings = account("savings", CurrencyCode.BYN);
        List<Operation> ops = List.of(
                new Income(UUID.randomUUID(), main.id(), new BigDecimal("1000.00"), "Зарплата", at, "Октябрь"),
                new Expense(
                        UUID.randomUUID(),
                        main.id(),
                        new BigDecimal("250.50"),
                        "Новая категория",
                        at.plusSeconds(1),
                        null),
                new Transfer(UUID.randomUUID(), main.id(), savings.id(), new BigDecimal("100.00"), at.plusSeconds(2)));

        ops.forEach(operations::saveOperation);

        assertThat(accounts.findAllAccounts()).containsExactly(main, savings);
        assertThat(operations.findAllOperations()).containsExactlyElementsOf(ops);
        assertThat(accounts.findBalance(main.id())).contains(new BigDecimal("649.50"));
        assertThat(accounts.findBalance(savings.id())).contains(new BigDecimal("100.00"));
    }

    @Test
    void expenseOverBalanceChangesNeitherBalanceNorOperations() {
        var main = account("main", CurrencyCode.BYN);
        var income = new Income(UUID.randomUUID(), main.id(), new BigDecimal("50.00"), "Зарплата", at, null);
        operations.saveOperation(income);

        var expense = new Expense(UUID.randomUUID(), main.id(), new BigDecimal("100.00"), "Техника", at, null);

        assertThatThrownBy(() -> operations.saveOperation(expense)).isInstanceOf(InsufficientFundsException.class);
        assertThat(accounts.findBalance(main.id())).contains(new BigDecimal("50.00"));
        assertThat(operations.findAllOperations()).containsExactly(income);
    }

    @Test
    void concurrentExpensesNeverOverdrawAccount() throws Exception {
        var main = account("main", CurrencyCode.BYN);
        operations.saveOperation(
                new Income(UUID.randomUUID(), main.id(), new BigDecimal("1000.00"), "Зарплата", at, null));

        int threads = 20;
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        operations.saveOperation(new Expense(
                                UUID.randomUUID(), main.id(), new BigDecimal("100.00"), "Продукты", at, null));
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
        }

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(10);
        assertThat(accounts.findBalance(main.id())).contains(new BigDecimal("0.00"));
        assertThat(operations.findAllOperations())
                .filteredOn(Expense.class::isInstance)
                .hasSize(10);
    }

    @Test
    void insertAllIsAtLeastFiveTimesFasterThanOneByOne() {
        int count = 10_000;
        var batched = account("batched", CurrencyCode.BYN);
        var oneByOne = account("one-by-one", CurrencyCode.BYN);
        List<Operation> batchedOps = incomes(batched.id(), count);
        List<Operation> oneByOneOps = incomes(oneByOne.id(), count);
        UUID warmUpAccountId = warmUp();

        long batchStart = System.nanoTime();
        operations.insertAll(batchedOps);
        Duration batchTime = Duration.ofNanos(System.nanoTime() - batchStart);

        long singleStart = System.nanoTime();
        oneByOneOps.forEach(operations::saveOperation);
        Duration singleTime = Duration.ofNanos(System.nanoTime() - singleStart);

        double ratio = (double) singleTime.toNanos() / batchTime.toNanos();
        log.warn(
                "{} операций: insertAll {} мс, по одной {} мс, быстрее в {} раз",
                count,
                batchTime.toMillis(),
                singleTime.toMillis(),
                String.format("%.1f", ratio));

        assertThat(operations.findAllOperations())
                .filteredOn(op -> !op.accountId().equals(warmUpAccountId))
                .hasSize(2 * count);
        BigDecimal expected = new BigDecimal(count).setScale(2);
        assertThat(accounts.findBalance(batched.id())).contains(expected);
        assertThat(accounts.findBalance(oneByOne.id())).contains(expected);
        assertThat(ratio).isGreaterThanOrEqualTo(5.0);
    }

    @Test
    void insertAllThatWouldOverdrawAccountChangesNothing() {
        var main = account("main", CurrencyCode.BYN);
        List<Operation> ops = List.of(
                new Income(UUID.randomUUID(), main.id(), new BigDecimal("100.00"), "Зарплата", at, null),
                new Expense(UUID.randomUUID(), main.id(), new BigDecimal("150.00"), "Техника", at, null));

        assertThatThrownBy(() -> operations.insertAll(ops)).isInstanceOf(InsufficientFundsException.class);
        assertThat(accounts.findBalance(main.id())).contains(new BigDecimal("0.00"));
        assertThat(operations.findAllOperations()).isEmpty();
    }

    private Account account(String name, CurrencyCode currency) {
        var account = new Account(UUID.randomUUID(), name, currency);
        accounts.saveAccount(account);
        return account;
    }

    private List<Operation> incomes(UUID accountId, int count) {
        return IntStream.range(0, count)
                .<Operation>mapToObj(i ->
                        new Income(UUID.randomUUID(), accountId, BigDecimal.ONE, "Зарплата", at.plusSeconds(i), null))
                .toList();
    }

    /** Прогрев JIT и пула соединений, чтобы первый замер не платил за запуск. */
    private UUID warmUp() {
        var warmUp = account("warm-up", CurrencyCode.BYN);
        operations.insertAll(incomes(warmUp.id(), 1_000));
        incomes(warmUp.id(), 200).forEach(operations::saveOperation);
        return warmUp.id();
    }
}
