package com.example.finance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceCalculatorTest {

    private final BalanceCalculator calculator = new BalanceCalculator();
    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    private static Income income(UUID accountId, String amount) {
        return new Income(UUID.randomUUID(), accountId, new BigDecimal(amount), "Зарплата", Instant.now(), null);
    }

    private static Expense expense(UUID accountId, String amount) {
        return new Expense(UUID.randomUUID(), accountId, new BigDecimal(amount), "Продукты", Instant.now(), null);
    }

    private static Transfer transfer(UUID from, UUID to, String amount) {
        return new Transfer(UUID.randomUUID(), from, to, new BigDecimal(amount), Instant.now());
    }

    @Test
    void incomeAddsAndExpenseSubtracts() {
        List<Operation> ops = List.of(
                income(accountA, "1000.00"),
                expense(accountA, "250.50"),
                income(accountB, "999.99"));

        assertEquals(new BigDecimal("749.50"), calculator.balanceOf(accountA, ops));
    }

    @Test
    void transferSubtractsFromSourceAccount() {
        List<Operation> ops = List.of(
                income(accountA, "1000.00"),
                transfer(accountA, accountB, "300.00"));

        assertEquals(new BigDecimal("700.00"), calculator.balanceOf(accountA, ops));
    }

    @Test
    void transferAddsToTargetAccount() {
        List<Operation> ops = List.of(
                income(accountB, "50.00"),
                transfer(accountA, accountB, "300.00"));

        assertEquals(new BigDecimal("350.00"), calculator.balanceOf(accountB, ops));
    }
}
