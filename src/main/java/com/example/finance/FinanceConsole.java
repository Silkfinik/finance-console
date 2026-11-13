package com.example.finance;

import com.example.finance.domain.BalanceCalculator;
import com.example.finance.report.ReportService;
import com.example.finance.storage.AccountRepository;
import com.example.finance.storage.Database;
import com.example.finance.storage.JdbcAccountRepository;
import com.example.finance.storage.JdbcOperationRepository;
import com.example.finance.storage.JsonFileRepository;
import com.example.finance.storage.OperationRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FinanceConsole {

    private static final Logger log = LoggerFactory.getLogger(FinanceConsole.class);

    static final String DATA_PATH_ENV = "FINANCE_DATA";
    static final Path DEFAULT_DATA_PATH = Path.of(System.getProperty("user.home"), ".finance-console", "data.json");

    static final String STORAGE_ENV = "FINANCE_STORAGE";
    static final String DB_URL_ENV = "FINANCE_DB_URL";
    static final String DB_USER_ENV = "FINANCE_DB_USER";
    static final String DB_PASSWORD_ENV = "FINANCE_DB_PASSWORD";
    static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/finance";
    static final String DEFAULT_DB_USER = "finance";
    static final String DEFAULT_DB_PASSWORD = "finance";

    enum Storage {
        JSON,
        POSTGRES
    }

    private final CommandHandler handler;
    private final ConsolePrinter out;

    public FinanceConsole(CommandHandler handler, ConsolePrinter out) {
        this.handler = Objects.requireNonNull(handler, "handler не может быть null");
        this.out = Objects.requireNonNull(out, "out не может быть null");
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        var printer = ConsolePrinter.stdout();
        switch (resolveStorage(env)) {
            case JSON -> {
                Path dataPath = resolveDataPath(env);
                log.info("Запуск, файл данных: {}", dataPath.toAbsolutePath());
                var storage = new JsonFileRepository(dataPath);
                run(storage, storage, printer);
            }
            case POSTGRES -> {
                String url = envOrDefault(env, DB_URL_ENV, DEFAULT_DB_URL);
                log.info("Запуск, база данных: {}", url);
                HikariDataSource dataSource;
                try {
                    dataSource = Database.createDataSource(
                            url,
                            envOrDefault(env, DB_USER_ENV, DEFAULT_DB_USER),
                            envOrDefault(env, DB_PASSWORD_ENV, DEFAULT_DB_PASSWORD));
                } catch (RuntimeException e) {
                    log.error("Не удалось подключиться к {}", url, e);
                    printer.println("Не удалось подключиться к базе данных " + url + ": " + e.getMessage());
                    System.exit(1);
                    return;
                }
                try (dataSource) {
                    Database.migrate(dataSource);
                    run(
                            new JdbcAccountRepository(dataSource, Database.LOCAL_USER_ID),
                            new JdbcOperationRepository(dataSource, Database.LOCAL_USER_ID),
                            printer);
                }
            }
        }
        log.info("Завершение работы");
    }

    private static void run(AccountRepository accounts, OperationRepository operations, ConsolePrinter printer) {
        var handler = new CommandHandler(
                accounts, operations, new BalanceCalculator(), printer, Clock.system(ReportService.DEFAULT_ZONE));
        new FinanceConsole(handler, printer).run(new BufferedReader(new InputStreamReader(System.in)));
    }

    static Storage resolveStorage(Map<String, String> env) {
        String value = env.get(STORAGE_ENV);
        if (value == null || value.isBlank()) {
            return Storage.JSON;
        }
        return switch (value.strip().toLowerCase()) {
            case "json" -> Storage.JSON;
            case "postgres" -> Storage.POSTGRES;
            default ->
                throw new IllegalArgumentException(
                        "Неизвестное значение " + STORAGE_ENV + ": " + value + ", допустимо: json, postgres");
        };
    }

    private static String envOrDefault(Map<String, String> env, String name, String defaultValue) {
        String value = env.get(name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    static Path resolveDataPath(Map<String, String> env) {
        String override = env.get(DATA_PATH_ENV);
        if (override == null || override.isBlank()) {
            return DEFAULT_DATA_PATH;
        }
        return Path.of(override.strip());
    }

    public void run(BufferedReader in) {
        out.println("Finance Console. Введите help для списка команд.");
        try {
            while (true) {
                out.prompt("> ");
                String line = in.readLine();
                if (line == null || !handler.handle(line)) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Не удалось прочитать ввод", e);
            throw new UncheckedIOException(e);
        }
    }
}
