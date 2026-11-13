package com.example.finance.storage;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface BalanceReader {
    /** Текущий баланс счёта или пусто, если такого счёта нет. */
    Optional<BigDecimal> findBalance(UUID accountId);
}
