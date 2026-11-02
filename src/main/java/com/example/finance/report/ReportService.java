package com.example.finance.report;

import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReportService {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Minsk");

    private static final Locale RUSSIAN = Locale.of("ru");

    private final ZoneId zone;

    public ReportService() {
        this(DEFAULT_ZONE);
    }

    public ReportService(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone не может быть null");
    }

    public Map<String, BigDecimal> expensesByCategory(List<Operation> ops, YearMonth month) {
        return expensesIn(ops, month).collect(Collectors.groupingBy(Expense::category, TreeMap::new, sumOfAmounts()));
    }

    public List<Expense> topExpenses(List<Operation> ops, int n) {
        return expenses(ops)
                .sorted(Comparator.comparing(Expense::amount).reversed().thenComparing(Expense::occurredAt))
                .limit(n)
                .toList();
    }

    public SortedMap<YearMonth, BigDecimal> monthlyNet(List<Operation> ops) {
        return ops.stream()
                .filter(op -> !(op instanceof Transfer))
                .collect(Collectors.groupingBy(
                        this::monthOf,
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, ReportService::signedAmount, BigDecimal::add)));
    }

    public Optional<Expense> largestExpense(List<Operation> ops) {
        return topExpenses(ops, 1).stream().findFirst();
    }

    public BigDecimal averageExpense(List<Operation> ops, YearMonth month) {
        List<Expense> expenses = expensesIn(ops, month).toList();
        BigDecimal total = expenses.stream().collect(sumOfAmounts());
        BigDecimal count = BigDecimal.valueOf(Math.max(1, expenses.size()));
        return total.divide(count, 2, RoundingMode.HALF_EVEN);
    }

    public Map<DayOfWeek, Long> expenseCountByWeekday(List<Operation> ops) {
        return expenses(ops)
                .collect(Collectors.groupingBy(
                        e -> e.occurredAt().atZone(zone).getDayOfWeek(),
                        () -> new EnumMap<>(DayOfWeek.class),
                        Collectors.counting()));
    }

    public Map<Boolean, List<Operation>> partitionByAmount(List<Operation> ops, BigDecimal threshold) {
        Objects.requireNonNull(threshold, "threshold не может быть null");
        return ops.stream().collect(Collectors.partitioningBy(op -> op.amount().compareTo(threshold) > 0));
    }

    public String categoriesJoined(List<Operation> ops) {
        return categories(ops).distinct().sorted(Collator.getInstance(RUSSIAN)).collect(Collectors.joining(", "));
    }

    public Map<String, Long> countByCategory(List<Operation> ops) {
        return categories(ops).collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
    }

    public List<YearMonth> monthsWithDeficit(List<Operation> ops) {
        return monthlyNet(ops).entrySet().stream()
                .filter(entry -> entry.getValue().signum() < 0)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static Stream<Expense> expenses(List<Operation> ops) {
        return ops.stream().filter(Expense.class::isInstance).map(Expense.class::cast);
    }

    private Stream<Expense> expensesIn(List<Operation> ops, YearMonth month) {
        Objects.requireNonNull(month, "month не может быть null");
        return expenses(ops).filter(e -> monthOf(e).equals(month));
    }

    private static Stream<String> categories(List<Operation> ops) {
        return ops.stream().flatMap(op -> categoryOf(op).stream());
    }

    private static Optional<String> categoryOf(Operation op) {
        return switch (op) {
            case Income income -> Optional.of(income.category());
            case Expense expense -> Optional.of(expense.category());
            case Transfer _ -> Optional.empty();
        };
    }

    private static BigDecimal signedAmount(Operation op) {
        return switch (op) {
            case Income income -> income.amount();
            case Expense expense -> expense.amount().negate();
            case Transfer _ -> BigDecimal.ZERO;
        };
    }

    private static Collector<Operation, ?, BigDecimal> sumOfAmounts() {
        return Collectors.reducing(BigDecimal.ZERO, Operation::amount, BigDecimal::add);
    }

    private YearMonth monthOf(Operation op) {
        return YearMonth.from(op.occurredAt().atZone(zone));
    }
}
