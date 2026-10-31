package com.example.finance.storage;

import com.example.finance.domain.Operation;

import java.util.List;

public interface OperationRepository {
    void saveOperation(Operation op);
    List<Operation> findAllOperations();
}
