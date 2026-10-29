package com.example.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Transfer(UUID id, UUID accountId, UUID targetAccountId,
                       BigDecimal amount, Instant occurredAt) implements Operation {
    public Transfer {
        Objects.requireNonNull(id, "id не может быть null");
        Objects.requireNonNull(accountId, "accountId не может быть null");
        Objects.requireNonNull(targetAccountId, "targetAccountId не может быть null");
        Objects.requireNonNull(occurredAt, "occurredAt не может быть null");

        OperationRules.requireValidAmount(amount);

        if (accountId.equals(targetAccountId)) {
            throw new IllegalArgumentException(
                    "Счёт-источник и счёт-получатель перевода должны различаться: " + accountId);
        }

        amount = amount.setScale(OperationRules.MAX_SCALE);
    }
}
