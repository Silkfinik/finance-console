package com.example.finance.storage;

import com.example.finance.domain.Operation;
import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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

public class JsonFileOperationRepository implements OperationRepository {

    private static final TypeReference<List<Operation>> OPERATIONS_TYPE = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .withConfigOverride(BigDecimal.class,
                    o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path file;

    public JsonFileOperationRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file не может быть null");
    }

    @Override
    public synchronized void save(Operation op) {
        Objects.requireNonNull(op, "op не может быть null");
        List<Operation> operations = new ArrayList<>(read());
        operations.add(op);
        write(operations);
    }

    @Override
    public synchronized List<Operation> findAll() {
        return List.copyOf(read());
    }

    private List<Operation> read() {
        if (Files.notExists(file)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(file.toFile(), OPERATIONS_TYPE);
        } catch (JacksonException e) {
            throw new StorageException("Не удалось прочитать операции из " + file, e);
        }
    }

    private void write(List<Operation> operations) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                MAPPER.writerFor(OPERATIONS_TYPE).writeValue(tmp.toFile(), operations);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | JacksonException e) {
            throw new StorageException("Не удалось записать операции в " + file, e);
        }
    }
}
