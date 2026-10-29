package com.example.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Income (UUID id, UUID accountId, BigDecimal amount,
                      String category, Instant occurredAt, String description) implements Operation {
    public Income {
        Objects.requireNonNull(id, "id не может быть null");
        Objects.requireNonNull(accountId, "accountId не может быть null");
        Objects.requireNonNull(category, "category не может быть null");
        Objects.requireNonNull(occurredAt, "occurredAt не может быть null");

        OperationRules.requireValidAmount(amount);
        OperationRules.requireValidDescription(description);

        amount = amount.setScale(OperationRules.MAX_SCALE);
    }
}
