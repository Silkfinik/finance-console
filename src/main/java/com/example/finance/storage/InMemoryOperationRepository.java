package com.example.finance.storage;

import com.example.finance.domain.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class InMemoryOperationRepository implements OperationRepository {

    private final List<Operation> operations = new ArrayList<>();

    @Override
    public synchronized void save(Operation op) {
        Objects.requireNonNull(op, "op не может быть null");
        operations.add(op);
    }

    @Override
    public synchronized List<Operation> findAll() {
        return List.copyOf(operations);
    }
}
