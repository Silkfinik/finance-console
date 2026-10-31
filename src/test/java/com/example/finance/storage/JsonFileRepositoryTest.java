package com.example.finance.storage;

import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileRepositoryTest {

    @TempDir
    Path dir;

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-31T12:00:00Z");

    private Path file() {
        return dir.resolve("operations.json");
    }

    @Test
    void savedOperationsOfAllKindsAreReadBackEqual() {
        List<Operation> ops = List.of(
                new Income(UUID.randomUUID(), accountA, new BigDecimal("1000.00"), "Зарплата", at, "Октябрь"),
                new Expense(UUID.randomUUID(), accountA, new BigDecimal("250.50"), "Продукты", at, null),
                new Transfer(UUID.randomUUID(), accountA, accountB, new BigDecimal("100.00"), at));

        ops.forEach(new JsonFileRepository(file())::saveOperation);

        assertEquals(ops, new JsonFileRepository(file()).findAllOperations());
    }

    @Test
    void missingFileIsReadAsEmptyList() {
        var repo = new JsonFileRepository(file());

        assertTrue(repo.findAllOperations().isEmpty());
    }

    @Test
    void corruptedFileThrowsStorageException() throws IOException {
        Files.writeString(file(), "[{\"kind\": \"INCOME\", \"id\": ");
        var repo = new JsonFileRepository(file());

        var e = assertThrows(StorageException.class, repo::findAllOperations);
        assertNotNull(e.getCause());
    }

    @Test
    void noTemporaryFilesLeftAfterManySaves() throws IOException {
        var repo = new JsonFileRepository(file());

        for (int i = 0; i < 100; i++) {
            repo.saveOperation(new Expense(UUID.randomUUID(), accountA, new BigDecimal("1.00"), "Кофе", at, null));
        }

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(file()), files.toList());
        }
        assertEquals(100, repo.findAllOperations().size());
    }
}
