package com.example.finance.storage;

import com.example.finance.domain.Operation;

import java.util.List;

public interface OperationRepository {
    void save(Operation op);
    List<Operation> findAll();
}
