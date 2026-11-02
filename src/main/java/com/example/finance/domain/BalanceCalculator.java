package com.example.finance.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class BalanceCalculator {

    public BigDecimal balanceOf(UUID accountId, List<Operation> ops) {
        Objects.requireNonNull(accountId, "accountId не может быть null");
        Objects.requireNonNull(ops, "ops не может быть null");

        return ops.stream()
                .map(op -> deltaFor(accountId, op))
                .reduce(BigDecimal.ZERO.setScale(OperationRules.MAX_SCALE), BigDecimal::add);
    }

    private BigDecimal deltaFor(UUID accountId, Operation op) {
        return switch (op) {
            case Income income -> income.accountId().equals(accountId) ? income.amount() : BigDecimal.ZERO;
            case Expense expense ->
                expense.accountId().equals(accountId) ? expense.amount().negate() : BigDecimal.ZERO;
            case Transfer transfer -> {
                if (transfer.accountId().equals(accountId)) {
                    yield transfer.amount().negate();
                }
                if (transfer.targetAccountId().equals(accountId)) {
                    yield transfer.amount();
                }
                yield BigDecimal.ZERO;
            }
        };
    }
}
