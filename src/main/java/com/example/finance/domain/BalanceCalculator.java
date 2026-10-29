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
            case Income(_, UUID account, BigDecimal amount, _, _, _) ->
                    account.equals(accountId) ? amount : BigDecimal.ZERO;
            case Expense(_, UUID account, BigDecimal amount, _, _, _) ->
                    account.equals(accountId) ? amount.negate() : BigDecimal.ZERO;
            case Transfer(_, UUID source, UUID target, BigDecimal amount, _) -> {
                if (source.equals(accountId)) {
                    yield amount.negate();
                }
                if (target.equals(accountId)) {
                    yield amount;
                }
                yield BigDecimal.ZERO;
            }
        };
    }
}
