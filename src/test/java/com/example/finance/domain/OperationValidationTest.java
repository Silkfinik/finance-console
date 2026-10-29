package com.example.finance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OperationValidationTest {

    private static Income income(String amount, String description) {
        return new Income(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount),
                "Зарплата", Instant.now(), description);
    }

    @Test
    void amountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> income("0", null));
    }

    @Test
    void amountMustNotExceedMillion() {
        assertThrows(IllegalArgumentException.class, () -> income("1000000.01", null));
    }

    @Test
    void amountMustHaveAtMostTwoDecimalPlaces() {
        assertThrows(IllegalArgumentException.class, () -> income("10.505", null));
    }

    @Test
    void descriptionMustNotExceed255Characters() {
        assertThrows(IllegalArgumentException.class, () -> income("10.00", "a".repeat(256)));
    }

    @Test
    void transferMustHaveDifferentAccounts() {
        UUID account = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new Transfer(UUID.randomUUID(), account, account, new BigDecimal("10.00"), Instant.now()));
    }
}
