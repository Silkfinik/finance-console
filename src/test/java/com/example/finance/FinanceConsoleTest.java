package com.example.finance;

import com.example.finance.domain.Account;
import com.example.finance.domain.BalanceCalculator;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.storage.InMemoryAccountRepository;
import com.example.finance.storage.InMemoryOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceConsoleTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryOperationRepository operations = new InMemoryOperationRepository();
    private FinanceConsole console;

    @BeforeEach
    void setUp() {
        console = new FinanceConsole(accounts, operations, new BalanceCalculator(),
                new PrintStream(buffer, true, UTF_8), clock);
    }

    private List<String> exec(String line) {
        buffer.reset();
        console.handleLine(line);
        return buffer.toString(UTF_8).lines().toList();
    }

    private List<String> run(String... lines) {
        buffer.reset();
        console.run(new BufferedReader(new StringReader(String.join("\n", lines) + "\n")));
        return buffer.toString(UTF_8).lines().toList();
    }

    // Commands

    @Test
    void accountAddCreatesAccount() {
        assertEquals(List.of("Счёт создан: main (BYN)"), exec("account add main byn"));

        List<Account> saved = accounts.findAllAccounts();
        assertEquals(1, saved.size());
        assertEquals("main", saved.getFirst().name());
        assertEquals(CurrencyCode.BYN, saved.getFirst().currency());
    }

    @Test
    void accountListShowsAccountsWithBalances() {
        exec("account add main BYN");
        exec("account add card USD");
        exec("income main 100 Зарплата");

        assertEquals(List.of("main 100.00 BYN", "card 0.00 USD"), exec("account list"));
    }

    @Test
    void incomeSavesOperationWithDescription() {
        exec("account add main BYN");

        assertEquals(List.of("Доход записан: 1000.00 BYN"), exec("income main 1000 Зарплата премия за октябрь"));

        Income income = assertInstanceOf(Income.class, operations.findAllOperations().getFirst());
        assertEquals(new BigDecimal("1000.00"), income.amount());
        assertEquals("Зарплата", income.category());
        assertEquals("премия за октябрь", income.description());
        assertEquals(clock.instant(), income.occurredAt());
    }

    @Test
    void expenseSavesOperationAndRejectsAmountAboveBalance() {
        exec("account add main BYN");
        exec("income main 100 Зарплата");

        assertEquals(List.of("Расход записан: 30.50 BYN"), exec("expense main 30,5 Кафе"));
        Expense expense = assertInstanceOf(Expense.class, operations.findAllOperations().getLast());
        assertEquals(new BigDecimal("30.50"), expense.amount());
        assertEquals("Кафе", expense.category());

        assertEquals(List.of("Ошибка: Недостаточно средств"), exec("expense main 70 Техника"));
        assertEquals(2, operations.findAllOperations().size());
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        exec("account add main BYN");
        exec("account add card BYN");
        exec("income main 100 Зарплата");

        assertEquals(List.of("Перевод выполнен: 40.00 BYN main -> card"), exec("transfer main card 40"));
        assertEquals(List.of("main 60.00 BYN", "card 40.00 BYN"), exec("account list"));
    }

    @Test
    void balanceShowsIncomeMinusExpenses() {
        exec("account add main BYN");
        exec("income main 100 Зарплата");
        exec("expense main 25 Кафе");

        assertEquals(List.of("main: 75.00 BYN"), exec("balance main"));
    }

    @Test
    void reportCategoriesSumsExpensesOfMonthByCategory() {
        exec("account add main BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 200 Продукты");
        exec("expense main 50 Продукты");
        exec("expense main 300 Аренда");

        assertEquals(List.of(
                "Расходы по категориям за 2026-10:",
                "  Аренда: 300.00 BYN",
                "  Продукты: 250.00 BYN"), exec("report categories 2026-10"));
        assertEquals(List.of("Нет расходов за 2026-09"), exec("report categories 2026-09"));
    }

    @Test
    void reportTopShowsLargestExpenses() {
        exec("account add main BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 10 Кофе");
        exec("expense main 300 Аренда");
        exec("expense main 50 Кафе обед");

        assertEquals(List.of(
                "Топ-2 расходов:",
                "  1. 2026-10-15 main Аренда 300.00 BYN",
                "  2. 2026-10-15 main Кафе 50.00 BYN (обед)"), exec("report top 2"));
    }

    @Test
    void reportMonthlySumsIncomesAndExpensesIgnoringTransfers() {
        exec("account add main BYN");
        exec("account add card BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 250 Продукты");
        exec("transfer main card 100");

        assertEquals(List.of("2026-10 BYN: доходы 1000.00, расходы 250.00, итого 750.00"),
                exec("report monthly"));
    }

    @Test
    void helpListsCommands() {
        List<String> output = exec("help");

        assertEquals("Команды:", output.getFirst());
        assertTrue(output.stream().anyMatch(line -> line.contains("report monthly")));
    }

    @Test
    void exitStopsReadingInput() {
        run("exit", "account add main BYN");

        assertTrue(accounts.findAllAccounts().isEmpty());
    }

    // Input errors

    @Test
    void unknownCommandPrintsOneLineAndContinues() {
        List<String> output = run("foo bar", "account list");

        assertEquals(List.of("> Ошибка: Неизвестная команда: foo. Введите help", "> Счетов нет"),
                output.subList(1, 3));
    }

    @Test
    void invalidAmountPrintsOneLineAndContinues() {
        exec("account add main BYN");

        List<String> output = run("income main abc Зарплата", "balance main");

        assertEquals(List.of("> Ошибка: Неверная сумма: abc", "> main: 0.00 BYN"), output.subList(1, 3));
    }

    @Test
    void missingAccountPrintsOneLineAndContinues() {
        List<String> output = run("balance nope", "account list");

        assertEquals(List.of("> Ошибка: Нет счёта: nope", "> Счетов нет"), output.subList(1, 3));
    }
}
