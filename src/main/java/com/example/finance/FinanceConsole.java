package com.example.finance;

import com.example.finance.domain.BalanceCalculator;
import com.example.finance.report.ReportService;
import com.example.finance.storage.JsonFileRepository;
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

    private final CommandHandler handler;
    private final ConsolePrinter out;

    public FinanceConsole(CommandHandler handler, ConsolePrinter out) {
        this.handler = Objects.requireNonNull(handler, "handler не может быть null");
        this.out = Objects.requireNonNull(out, "out не может быть null");
    }

    public static void main(String[] args) {
        Path dataPath = resolveDataPath(System.getenv());
        log.info("Запуск, файл данных: {}", dataPath.toAbsolutePath());
        var storage = new JsonFileRepository(dataPath);
        var printer = ConsolePrinter.stdout();
        var handler = new CommandHandler(
                storage, storage, new BalanceCalculator(), printer, Clock.system(ReportService.DEFAULT_ZONE));
        new FinanceConsole(handler, printer).run(new BufferedReader(new InputStreamReader(System.in)));
        log.info("Завершение работы");
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
