package com.example.finance.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Income.class, name = "INCOME"),
        @JsonSubTypes.Type(value = Expense.class, name = "EXPENSE"),
        @JsonSubTypes.Type(value = Transfer.class, name = "TRANSFER")
})
public sealed interface Operation permits Income, Expense, Transfer {
    UUID id();
    UUID accountId();
    BigDecimal amount();
    Instant occurredAt();
}
