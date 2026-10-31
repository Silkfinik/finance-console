package com.example.finance.storage;

import com.example.finance.domain.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class InMemoryAccountRepository implements AccountRepository {

    private final List<Account> accounts = new ArrayList<>();

    @Override
    public synchronized void saveAccount(Account account) {
        Objects.requireNonNull(account, "account не может быть null");
        accounts.add(account);
    }

    @Override
    public synchronized List<Account> findAllAccounts() {
        return List.copyOf(accounts);
    }
}
