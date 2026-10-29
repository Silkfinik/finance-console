package com.example.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public sealed interface Operation permits Income, Expense, Transfer {
    UUID id();
    UUID accountId();
    BigDecimal amount();
    Instant occurredAt();
}
