package com.example.finance.storage;

import com.example.finance.domain.Account;

import java.util.List;

public interface AccountRepository {
    void saveAccount(Account account);
    List<Account> findAllAccounts();
}
