package com.example.finance.domain;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
        super("Недостаточно средств на счёте " + accountId);
    }
}
