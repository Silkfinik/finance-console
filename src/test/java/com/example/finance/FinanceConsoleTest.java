package com.example.finance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.finance.domain.BalanceCalculator;
import com.example.finance.storage.InMemoryAccountRepository;
import com.example.finance.storage.InMemoryOperationRepository;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinanceConsoleTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryOperationRepository operations = new InMemoryOperationRepository();
    private CommandHandler handler;
    private FinanceConsole console;

    @BeforeEach
    void setUp() {
        var out = new ConsolePrinter(new PrintStream(buffer, true, UTF_8));
        handler = new CommandHandler(accounts, operations, new BalanceCalculator(), out, clock);
        console = new FinanceConsole(handler, out);
    }

    private List<String> run(String... lines) {
        buffer.reset();
        console.run(new BufferedReader(new StringReader(String.join("\n", lines) + "\n")));
        return buffer.toString(UTF_8).lines().toList();
    }

    @Test
    void exitStopsReadingInput() {
        run("exit", "account add main BYN");

        assertThat(accounts.findAllAccounts()).isEmpty();
    }

    @Test
    void unknownCommandPrintsOneLineAndContinues() {
        List<String> output = run("foo bar", "account list");

        assertThat(output.subList(1, 3))
                .containsExactly("> Ошибка: Неизвестная команда: foo. Введите help", "> Счетов нет");
    }

    @Test
    void invalidAmountPrintsOneLineAndContinues() {
        handler.handle("account add main BYN");

        List<String> output = run("income main abc Зарплата", "balance main");

        assertThat(output.subList(1, 3)).containsExactly("> Ошибка: Неверная сумма: abc", "> main: 0.00 BYN");
    }

    @Test
    void missingAccountPrintsOneLineAndContinues() {
        List<String> output = run("balance nope", "account list");

        assertThat(output.subList(1, 3)).containsExactly("> Ошибка: Нет счёта: nope", "> Счетов нет");
    }
}
