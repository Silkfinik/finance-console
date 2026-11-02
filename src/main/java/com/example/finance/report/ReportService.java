package com.example.finance.report;

import com.example.finance.domain.Account;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import com.example.finance.storage.AccountRepository;
import com.example.finance.storage.OperationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ReportService {

    private final AccountRepository accounts;
    private final OperationRepository operations;
    private final ZoneId zone;

    public ReportService(AccountRepository accounts, OperationRepository operations, ZoneId zone) {
        this.accounts = Objects.requireNonNull(accounts, "accounts не может быть null");
        this.operations = Objects.requireNonNull(operations, "operations не может быть null");
        this.zone = Objects.requireNonNull(zone, "zone не может быть null");
    }

    public List<String> categories(YearMonth month) {
        Map<UUID, Account> byId = accountsById();

        Map<CurrencyCode, Map<String, BigDecimal>> totals = new TreeMap<>();
        for (Expense e : expenses()) {
            if (!monthOf(e.occurredAt()).equals(month)) {
                continue;
            }
            CurrencyCode currency = byId.get(e.accountId()).currency();
            totals.computeIfAbsent(currency, _ -> new TreeMap<>()).merge(e.category(), e.amount(), BigDecimal::add);
        }

        if (totals.isEmpty()) {
            return List.of("Нет расходов за " + month);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Расходы по категориям за " + month + ":");
        totals.forEach((currency, byCategory) -> byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(entry -> lines.add("  " + entry.getKey() + ": " + format(entry.getValue()) + " " + currency)));
        return lines;
    }

    public List<String> top(int n) {
        Map<UUID, Account> byId = accountsById();

        List<Expense> top = expenses().stream()
                .sorted(Comparator.comparing(Expense::amount).reversed().thenComparing(Expense::occurredAt))
                .limit(n)
                .toList();

        if (top.isEmpty()) {
            return List.of("Расходов нет");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Топ-" + n + " расходов:");
        for (int i = 0; i < top.size(); i++) {
            Expense e = top.get(i);
            Account account = byId.get(e.accountId());
            String line = "  " + (i + 1) + ". " + LocalDate.ofInstant(e.occurredAt(), zone)
                    + " " + account.name() + " " + e.category()
                    + " " + format(e.amount()) + " " + account.currency();
            if (e.description() != null) {
                line += " (" + e.description() + ")";
            }
            lines.add(line);
        }
        return lines;
    }

    public List<String> monthly() {
        Map<UUID, Account> byId = accountsById();

        record MonthKey(YearMonth month, CurrencyCode currency) {}
        Map<MonthKey, BigDecimal[]> totals =
                new TreeMap<>(Comparator.comparing(MonthKey::month).thenComparing(MonthKey::currency));

        for (Operation op : operations.findAllOperations()) {
            int column = switch (op) {
                case Income _ -> 0;
                case Expense _ -> 1;
                case Transfer _ -> -1;
            };
            if (column < 0) {
                continue;
            }
            var key = new MonthKey(
                    monthOf(op.occurredAt()), byId.get(op.accountId()).currency());
            BigDecimal[] row = totals.computeIfAbsent(key, _ -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            row[column] = row[column].add(op.amount());
        }

        if (totals.isEmpty()) {
            return List.of("Нет доходов и расходов");
        }
        List<String> lines = new ArrayList<>();
        totals.forEach((key, row) -> lines.add(key.month() + " " + key.currency()
                + ": доходы " + format(row[0])
                + ", расходы " + format(row[1])
                + ", итого " + format(row[0].subtract(row[1]))));
        return lines;
    }

    private Map<UUID, Account> accountsById() {
        return accounts.findAllAccounts().stream().collect(Collectors.toMap(Account::id, Function.identity()));
    }

    private List<Expense> expenses() {
        return operations.findAllOperations().stream()
                .filter(Expense.class::isInstance)
                .map(Expense.class::cast)
                .toList();
    }

    private YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(zone));
    }

    private static String format(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }
}
