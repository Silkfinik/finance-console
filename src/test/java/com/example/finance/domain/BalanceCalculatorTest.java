package com.example.finance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BalanceCalculatorTest {

    private final BalanceCalculator calculator = new BalanceCalculator();
    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    private static Operation income(UUID accountId, String amount) {
        return new com.example.finance.domain.Income(
                UUID.randomUUID(), accountId, new BigDecimal(amount), "Зарплата", Instant.now(), null);
    }

    private static Operation expense(UUID accountId, String amount) {
        return new com.example.finance.domain.Expense(
                UUID.randomUUID(), accountId, new BigDecimal(amount), "Продукты", Instant.now(), null);
    }

    private static Operation transfer(UUID from, UUID to, String amount) {
        return new com.example.finance.domain.Transfer(
                UUID.randomUUID(), from, to, new BigDecimal(amount), Instant.now());
    }

    @Nested
    class Income {

        @Test
        void addsAmountToItsAccountOnly() {
            List<Operation> ops = List.of(income(accountA, "1000.00"), income(accountB, "999.99"));

            assertThat(calculator.balanceOf(accountA, ops)).isEqualTo(new BigDecimal("1000.00"));
        }
    }

    @Nested
    class Expense {

        @Test
        void subtractsAmountFromItsAccount() {
            List<Operation> ops = List.of(income(accountA, "1000.00"), expense(accountA, "250.50"));

            assertThat(calculator.balanceOf(accountA, ops)).isEqualTo(new BigDecimal("749.50"));
        }
    }

    @Nested
    class Transfer {

        @Test
        void subtractsFromSourceAccount() {
            List<Operation> ops = List.of(income(accountA, "1000.00"), transfer(accountA, accountB, "300.00"));

            assertThat(calculator.balanceOf(accountA, ops)).isEqualTo(new BigDecimal("700.00"));
        }

        @Test
        void addsToTargetAccount() {
            List<Operation> ops = List.of(income(accountB, "50.00"), transfer(accountA, accountB, "300.00"));

            assertThat(calculator.balanceOf(accountB, ops)).isEqualTo(new BigDecimal("350.00"));
        }
    }
}
