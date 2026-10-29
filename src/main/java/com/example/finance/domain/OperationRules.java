package com.example.finance.domain;

import java.math.BigDecimal;
import java.util.Objects;

final class OperationRules {
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    static final int MAX_SCALE = 2;
    static final int MAX_DESCRIPTION_LENGTH = 255;

    private OperationRules() {
    }

    static void requireValidAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount не может быть null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Сумма должна быть больше 0, получено: " + amount);
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Сумма не может быть больше " + MAX_AMOUNT + ", получено: " + amount);
        }
        if (amount.stripTrailingZeros().scale() > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "Сумма может иметь не больше " + MAX_SCALE + " знаков после запятой, получено: " + amount);
        }
    }

    static void requireValidDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Описание не может быть длиннее " + MAX_DESCRIPTION_LENGTH + " символов, получено: "
                            + description.length());
        }
    }
}
