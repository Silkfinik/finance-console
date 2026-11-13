package com.example.finance.storage;

import com.example.finance.domain.Account;
import com.example.finance.domain.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public class JdbcAccountRepository implements AccountRepository {

    private static final String INSERT = "INSERT INTO accounts (id, user_id, name, currency) VALUES (?, ?, ?, ?)";
    private static final String SELECT_ALL =
            "SELECT id, name, currency FROM accounts WHERE user_id = ? ORDER BY created_at, name";
    private static final String SELECT_BALANCE = "SELECT balance FROM accounts WHERE id = ? AND user_id = ?";

    private final DataSource dataSource;
    private final UUID userId;

    public JdbcAccountRepository(DataSource dataSource, UUID userId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource не может быть null");
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
    }

    @Override
    public void saveAccount(Account account) {
        Objects.requireNonNull(account, "account не может быть null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, account.id());
            ps.setObject(2, userId);
            ps.setString(3, account.name());
            ps.setString(4, account.currency().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Не удалось сохранить счёт " + account.name(), e);
        }
    }

    @Override
    public List<Account> findAllAccounts() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_ALL)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Account> accounts = new ArrayList<>();
                while (rs.next()) {
                    accounts.add(new Account(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            CurrencyCode.valueOf(rs.getString("currency"))));
                }
                return accounts;
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось прочитать счета", e);
        }
    }

    /** Баланс из колонки accounts.balance, которую ведёт {@link JdbcOperationRepository}. */
    public Optional<BigDecimal> findBalance(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId не может быть null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_BALANCE)) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getBigDecimal("balance")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("Не удалось прочитать баланс счёта " + accountId, e);
        }
    }
}
