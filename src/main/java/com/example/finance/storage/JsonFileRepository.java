package com.example.finance.storage;

import com.example.finance.domain.Account;
import com.example.finance.domain.Operation;
import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JsonFileRepository implements OperationRepository, AccountRepository {

    record StorageData(List<Account> accounts, List<Operation> operations) {
        StorageData {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .withConfigOverride(BigDecimal.class,
                    o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path file;

    public JsonFileRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file не может быть null");
    }

    @Override
    public synchronized void saveOperation(Operation op) {
        Objects.requireNonNull(op, "op не может быть null");
        StorageData data = read();
        List<Operation> operations = new ArrayList<>(data.operations());
        operations.add(op);
        write(new StorageData(data.accounts(), operations));
    }

    @Override
    public synchronized List<Operation> findAllOperations() {
        return read().operations();
    }

    @Override
    public synchronized void saveAccount(Account account) {
        Objects.requireNonNull(account, "account не может быть null");
        StorageData data = read();
        List<Account> accounts = new ArrayList<>(data.accounts());
        accounts.add(account);
        write(new StorageData(accounts, data.operations()));
    }

    @Override
    public synchronized List<Account> findAllAccounts() {
        return read().accounts();
    }

    private StorageData read() {
        if (Files.notExists(file)) {
            return new StorageData(List.of(), List.of());
        }
        try {
            return MAPPER.readValue(file.toFile(), StorageData.class);
        } catch (JacksonException e) {
            throw new StorageException("Не удалось прочитать данные из " + file, e);
        }
    }

    private void write(StorageData data) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                MAPPER.writeValue(tmp.toFile(), data);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | JacksonException e) {
            throw new StorageException("Не удалось записать данные в " + file, e);
        }
    }
}
