package com.example.finance.storage;

import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.InsufficientFundsException;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Хранит операции в таблице operations и в той же транзакции ведёт accounts.balance. Списание идёт условным UPDATE,
 * поэтому баланс не уходит в минус даже при одновременных расходах с одного счёта.
 */
public class JdbcOperationRepository implements OperationRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcOperationRepository.class);

    static final int BATCH_SIZE = 500;

    private static final String DEBIT = "UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?";
    private static final String CREDIT = "UPDATE accounts SET balance = balance + ? WHERE id = ?";
    private static final String INSERT = """
            INSERT INTO operations (id, account_id, type, amount, category_id, target_account_id, description, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final String SELECT_CATEGORY = """
            SELECT id FROM categories
            WHERE name = ? AND type = ? AND (user_id IS NULL OR user_id = ?)
            ORDER BY user_id NULLS FIRST
            LIMIT 1""";
    private static final String INSERT_CATEGORY = """
            INSERT INTO categories (user_id, name, type) VALUES (?, ?, ?)
            ON CONFLICT (user_id, name, type) DO NOTHING
            RETURNING id""";
    private static final String SELECT_ALL = """
            SELECT o.id, o.account_id, o.type, o.amount, c.name AS category,
                   o.target_account_id, o.description, o.occurred_at
            FROM operations o
            JOIN accounts a ON a.id = o.account_id
            LEFT JOIN categories c ON c.id = o.category_id
            WHERE a.user_id = ?
            ORDER BY o.occurred_at, o.created_at, o.id""";

    private static final String CHECK_VIOLATION = "23514";

    private record CategoryKey(String type, String name) {}

    private final DataSource dataSource;
    private final UUID userId;
    private final Consumer<Set<UUID>> afterCommit;

    public JdbcOperationRepository(DataSource dataSource, UUID userId) {
        this(dataSource, userId, _ -> {});
    }

    /**
     * @param afterCommit получает id счетов, балансы которых изменила транзакция; вызывается только после успешного
     *     commit, когда соединение уже возвращено в пул
     */
    public JdbcOperationRepository(DataSource dataSource, UUID userId, Consumer<Set<UUID>> afterCommit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource не может быть null");
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
        this.afterCommit = Objects.requireNonNull(afterCommit, "afterCommit не может быть null");
    }

    /**
     * Сохраняет операцию и меняет балансы счетов в одной транзакции.
     *
     * @throws InsufficientFundsException если на счёте списания меньше суммы операции; ничего не меняется
     */
    @Override
    public void saveOperation(Operation op) {
        Objects.requireNonNull(op, "op не может быть null");
        Set<UUID> affected = switch (op) {
            case Income income -> Set.of(income.accountId());
            case Expense expense -> Set.of(expense.accountId());
            case Transfer transfer -> Set.of(transfer.accountId(), transfer.targetAccountId());
        };
        inTransaction("Не удалось сохранить операцию " + op.id(), affected, c -> {
            switch (op) {
                case Income income -> credit(c, income.accountId(), income.amount());
                case Expense expense -> debit(c, expense.accountId(), expense.amount());
                case Transfer transfer -> {
                    // Строки счетов блокируются в одном порядке, чтобы встречные переводы не ловили deadlock.
                    if (transfer.accountId().compareTo(transfer.targetAccountId()) < 0) {
                        debit(c, transfer.accountId(), transfer.amount());
                        credit(c, transfer.targetAccountId(), transfer.amount());
                    } else {
                        credit(c, transfer.targetAccountId(), transfer.amount());
                        debit(c, transfer.accountId(), transfer.amount());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                bindInsert(ps, op, categoryId(c, op, new HashMap<>()));
                ps.executeUpdate();
            }
        });
    }

    /**
     * Вставляет операции одной транзакцией: INSERT уходят через addBatch пачками по {@value #BATCH_SIZE}, балансы
     * счетов меняются на суммарную разницу по каждому счёту.
     *
     * @throws InsufficientFundsException если после всех операций баланс какого-то счёта стал бы отрицательным; ничего
     *     не меняется
     */
    public void insertAll(List<Operation> ops) {
        Objects.requireNonNull(ops, "ops не может быть null");
        if (ops.isEmpty()) {
            return;
        }
        Map<UUID, BigDecimal> deltas = deltas(ops);
        inTransaction("Не удалось сохранить " + ops.size() + " операций", deltas.keySet(), c -> {
            applyDeltas(c, deltas);
            Map<CategoryKey, Long> categories = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                int pending = 0;
                for (Operation op : ops) {
                    bindInsert(ps, op, categoryId(c, op, categories));
                    ps.addBatch();
                    if (++pending == BATCH_SIZE) {
                        ps.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    ps.executeBatch();
                }
            }
            log.debug("Вставлено операций: {}", ops.size());
        });
    }

    @Override
    public List<Operation> findAllOperations() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_ALL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Operation> ops = new ArrayList<>();
                while (rs.next()) {
                    ops.add(toOperation(rs));
                }
                return ops;
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось прочитать операции", e);
        }
    }

    // Transactions

    @FunctionalInterface
    private interface TransactionBody {
        void run(Connection c) throws SQLException;
    }

    private void inTransaction(String errorMessage, Set<UUID> affectedAccounts, TransactionBody body) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                body.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException(errorMessage, e);
        }
        afterCommit.accept(Set.copyOf(affectedAccounts));
    }

    private static void debit(Connection c, UUID accountId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(DEBIT)) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, accountId);
            ps.setBigDecimal(3, amount);
            if (ps.executeUpdate() == 0) {
                throw new InsufficientFundsException(accountId);
            }
        }
    }

    private static void credit(Connection c, UUID accountId, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CREDIT)) {
            ps.setBigDecimal(1, amount);
            ps.setObject(2, accountId);
            if (ps.executeUpdate() == 0) {
                throw new StorageException("Нет счёта " + accountId);
            }
        }
    }

    private static Map<UUID, BigDecimal> deltas(List<Operation> ops) {
        // TreeMap: тот же порядок блокировки строк, что и в saveOperation.
        Map<UUID, BigDecimal> deltas = new TreeMap<>();
        for (Operation op : ops) {
            switch (op) {
                case Income income -> deltas.merge(income.accountId(), income.amount(), BigDecimal::add);
                case Expense expense ->
                    deltas.merge(expense.accountId(), expense.amount().negate(), BigDecimal::add);
                case Transfer transfer -> {
                    deltas.merge(transfer.accountId(), transfer.amount().negate(), BigDecimal::add);
                    deltas.merge(transfer.targetAccountId(), transfer.amount(), BigDecimal::add);
                }
            }
        }
        return deltas;
    }

    private static void applyDeltas(Connection c, Map<UUID, BigDecimal> deltas) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CREDIT)) {
            for (Map.Entry<UUID, BigDecimal> delta : deltas.entrySet()) {
                ps.setBigDecimal(1, delta.getValue());
                ps.setObject(2, delta.getKey());
                try {
                    if (ps.executeUpdate() == 0) {
                        throw new StorageException("Нет счёта " + delta.getKey());
                    }
                } catch (SQLException e) {
                    if (CHECK_VIOLATION.equals(e.getSQLState())) {
                        throw new InsufficientFundsException(delta.getKey());
                    }
                    throw e;
                }
            }
        }
    }

    // Categories

    private Long categoryId(Connection c, Operation op, Map<CategoryKey, Long> cache) throws SQLException {
        CategoryKey key = switch (op) {
            case Income income -> new CategoryKey("INCOME", income.category());
            case Expense expense -> new CategoryKey("EXPENSE", expense.category());
            case Transfer _ -> null;
        };
        if (key == null) {
            return null;
        }
        Long id = cache.get(key);
        if (id == null) {
            id = resolveCategory(c, key);
            cache.put(key, id);
        }
        return id;
    }

    private long resolveCategory(Connection c, CategoryKey key) throws SQLException {
        Long existing = selectCategory(c, key);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ps = c.prepareStatement(INSERT_CATEGORY)) {
            ps.setObject(1, userId);
            ps.setString(2, key.name());
            ps.setString(3, key.type());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        // Ту же категорию только что создала параллельная транзакция.
        Long created = selectCategory(c, key);
        if (created == null) {
            throw new SQLException("Категория не найдена после вставки: " + key);
        }
        return created;
    }

    private Long selectCategory(Connection c, CategoryKey key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_CATEGORY)) {
            ps.setString(1, key.name());
            ps.setString(2, key.type());
            ps.setObject(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    // Mapping

    private static void bindInsert(PreparedStatement ps, Operation op, Long categoryId) throws SQLException {
        ps.setObject(1, op.id());
        ps.setObject(2, op.accountId());
        ps.setBigDecimal(4, op.amount());
        if (categoryId == null) {
            ps.setNull(5, Types.BIGINT);
        } else {
            ps.setLong(5, categoryId);
        }
        ps.setObject(8, OffsetDateTime.ofInstant(op.occurredAt(), ZoneOffset.UTC));
        switch (op) {
            case Income income -> {
                ps.setString(3, "INCOME");
                ps.setNull(6, Types.OTHER);
                ps.setString(7, income.description());
            }
            case Expense expense -> {
                ps.setString(3, "EXPENSE");
                ps.setNull(6, Types.OTHER);
                ps.setString(7, expense.description());
            }
            case Transfer transfer -> {
                ps.setString(3, "TRANSFER");
                ps.setObject(6, transfer.targetAccountId());
                ps.setNull(7, Types.VARCHAR);
            }
        }
    }

    private static Operation toOperation(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID accountId = rs.getObject("account_id", UUID.class);
        BigDecimal amount = rs.getBigDecimal("amount");
        Instant occurredAt = rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
        String type = rs.getString("type");
        return switch (type) {
            case "INCOME" ->
                new Income(id, accountId, amount, rs.getString("category"), occurredAt, rs.getString("description"));
            case "EXPENSE" ->
                new Expense(id, accountId, amount, rs.getString("category"), occurredAt, rs.getString("description"));
            case "TRANSFER" ->
                new Transfer(id, accountId, rs.getObject("target_account_id", UUID.class), amount, occurredAt);
            default -> throw new SQLException("Неизвестный тип операции: " + type);
        };
    }
}
