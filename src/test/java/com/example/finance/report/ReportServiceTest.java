package com.example.finance.report;

import static com.example.finance.report.TestData.CAFE_AT_MONTH_BOUNDARY;
import static com.example.finance.report.TestData.LAPTOP;
import static com.example.finance.report.TestData.RENT_OCTOBER;
import static com.example.finance.report.TestData.RENT_SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.example.finance.domain.Operation;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private final ReportService reports = new ReportService();
    private final List<Operation> ops = TestData.sample();

    @Test
    void sampleHasThirtyOperations() {
        assertThat(ops).hasSize(30);
    }

    @Test
    void expensesByCategorySumsExpensesOfMonth() {
        assertThat(reports.expensesByCategory(ops, OCTOBER))
                .containsExactly(
                        entry("Аренда", new BigDecimal("450.00")),
                        entry("Кафе", new BigDecimal("35.00")),
                        entry("Подписки", new BigDecimal("99.99")),
                        entry("Продукты", new BigDecimal("300.00")),
                        entry("Техника", new BigDecimal("1500.00")),
                        entry("Транспорт", new BigDecimal("60.00")));
    }

    @Test
    void topExpensesSortsByAmountDescendingThenByTimeAscending() {
        assertThat(reports.topExpenses(ops, 3)).containsExactly(LAPTOP, RENT_SEPTEMBER, RENT_OCTOBER);
    }

    @Test
    void monthlyNetIsIncomesMinusExpensesIgnoringTransfers() {
        assertThat(reports.monthlyNet(ops))
                .containsExactly(
                        entry(SEPTEMBER, new BigDecimal("1259.01")),
                        entry(OCTOBER, new BigDecimal("-344.99")),
                        entry(NOVEMBER, new BigDecimal("1289.51")));
    }

    @Test
    void largestExpenseIsTheBiggestOne() {
        assertThat(reports.largestExpense(ops)).contains(LAPTOP);
        assertThat(reports.largestExpense(List.of())).isEmpty();
    }

    @Test
    void averageExpenseIsRoundedHalfEvenToTwoDigits() {
        assertThat(reports.averageExpense(ops, OCTOBER)).isEqualTo(new BigDecimal("349.28"));
        assertThat(reports.averageExpense(ops, NOVEMBER)).isEqualTo(new BigDecimal("130.07"));
        assertThat(reports.averageExpense(ops, YearMonth.of(2026, 12))).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void expenseCountByWeekdayUsesMinskTime() {
        assertThat(reports.expenseCountByWeekday(ops))
                .containsExactly(
                        entry(DayOfWeek.MONDAY, 2L),
                        entry(DayOfWeek.TUESDAY, 1L),
                        entry(DayOfWeek.WEDNESDAY, 1L),
                        entry(DayOfWeek.THURSDAY, 4L),
                        entry(DayOfWeek.FRIDAY, 1L),
                        entry(DayOfWeek.SATURDAY, 7L),
                        entry(DayOfWeek.SUNDAY, 5L));
    }

    @Test
    void partitionByAmountPutsAmountsAboveThresholdIntoTrue() {
        var parts = reports.partitionByAmount(ops, new BigDecimal("450.00"));

        assertThat(parts.get(true))
                .extracting(Operation::amount)
                .containsExactly(
                        new BigDecimal("2000.00"),
                        new BigDecimal("2000.00"),
                        new BigDecimal("1500.00"),
                        new BigDecimal("2000.00"));
        assertThat(parts.get(false)).hasSize(26).contains(RENT_SEPTEMBER, RENT_OCTOBER);
    }

    @Test
    void categoriesJoinedListsUniqueCategoriesAlphabetically() {
        assertThat(reports.categoriesJoined(ops))
                .isEqualTo("Аренда, Зарплата, Кафе, Подарки, Подписки, Подработка, Продукты, Техника, Транспорт");
    }

    @Test
    void countByCategoryCountsIncomesAndExpenses() {
        assertThat(reports.countByCategory(ops))
                .containsExactly(
                        entry("Аренда", 3L),
                        entry("Зарплата", 3L),
                        entry("Кафе", 5L),
                        entry("Подарки", 1L),
                        entry("Подписки", 3L),
                        entry("Подработка", 3L),
                        entry("Продукты", 5L),
                        entry("Техника", 1L),
                        entry("Транспорт", 3L));
    }

    @Test
    void monthsWithDeficitAreMonthsWhereExpensesExceedIncomes() {
        assertThat(reports.monthsWithDeficit(ops)).containsExactly(OCTOBER);
    }

    @Test
    void operationAt2330UtcOnOctober31BelongsToNovemberInMinsk() {
        List<Operation> boundary = List.of(CAFE_AT_MONTH_BOUNDARY);

        assertThat(reports.monthlyNet(boundary)).containsOnlyKeys(NOVEMBER);
        assertThat(reports.expensesByCategory(boundary, OCTOBER)).isEmpty();
        assertThat(reports.expensesByCategory(ops, NOVEMBER)).containsEntry("Кафе", new BigDecimal("130.50"));
        assertThat(reports.expenseCountByWeekday(boundary)).containsOnlyKeys(DayOfWeek.SUNDAY);
    }
}
