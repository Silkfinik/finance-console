package com.example.finance.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OperationValidationTest {

    private static Income income(String amount, String description) {
        return new Income(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "Зарплата", Instant.now(), description);
    }

    @ParameterizedTest(name = "[{index}] {arguments}")
    @CsvSource(useHeadersInDisplayName = true, textBlock = """
            amount,     valid
            0,          false
            -1,         false
            0.001,      false
            1000000.01, false
            0.01,       true
            1,          true
            999999.99,  true
            1000000.00, true
            """)
    void amountValidation(String amount, boolean valid) {
        ThrowingCallable create = () -> income(amount, null);

        if (valid) {
            assertThatCode(create).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(create).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void descriptionMustNotExceed255Characters() {
        assertThatThrownBy(() -> income("10.00", "a".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferMustHaveDifferentAccounts() {
        UUID account = UUID.randomUUID();

        assertThatThrownBy(
                        () -> new Transfer(UUID.randomUUID(), account, account, new BigDecimal("10.00"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
