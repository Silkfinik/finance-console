package com.example.finance;

import com.example.finance.domain.Account;
import com.example.finance.domain.BalanceCalculator;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.storage.InMemoryAccountRepository;
import com.example.finance.storage.InMemoryOperationRepository;
import com.example.finance.storage.OperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandHandlerTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(buffer, true, UTF_8);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    private final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    private final InMemoryOperationRepository operations = new InMemoryOperationRepository();
    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CommandHandler(accounts, operations, new BalanceCalculator(), out, clock);
    }

    private List<String> exec(String line) {
        buffer.reset();
        handler.handle(line);
        return buffer.toString(UTF_8).lines().toList();
    }

    @Test
    void accountAddCreatesAccount() {
        assertThat(exec("account add main byn")).containsExactly("Счёт создан: main (BYN)");

        assertThat(accounts.findAllAccounts())
                .singleElement()
                .extracting(Account::name, Account::currency)
                .containsExactly("main", CurrencyCode.BYN);
    }

    @Test
    void accountListShowsAccountsWithBalances() {
        exec("account add main BYN");
        exec("account add card USD");
        exec("income main 100 Зарплата");

        assertThat(exec("account list")).containsExactly("main 100.00 BYN", "card 0.00 USD");
    }

    @Test
    void incomeSavesOperationWithDescription() {
        exec("account add main BYN");

        assertThat(exec("income main 1000 Зарплата премия за октябрь"))
                .containsExactly("Доход записан: 1000.00 BYN");

        assertThat(operations.findAllOperations())
                .singleElement()
                .asInstanceOf(type(Income.class))
                .extracting(Income::amount, Income::category, Income::description, Income::occurredAt)
                .containsExactly(new BigDecimal("1000.00"), "Зарплата", "премия за октябрь", clock.instant());
    }

    @Test
    void expenseSavesOperationAndRejectsAmountAboveBalance() {
        exec("account add main BYN");
        exec("income main 100 Зарплата");

        assertThat(exec("expense main 30,5 Кафе")).containsExactly("Расход записан: 30.50 BYN");
        assertThat(operations.findAllOperations().getLast())
                .asInstanceOf(type(Expense.class))
                .extracting(Expense::amount, Expense::category)
                .containsExactly(new BigDecimal("30.50"), "Кафе");

        assertThat(exec("expense main 70 Техника")).containsExactly("Ошибка: Недостаточно средств");
        assertThat(operations.findAllOperations()).hasSize(2);
    }

    @Test
    void transferMovesMoneyBetweenAccounts() {
        exec("account add main BYN");
        exec("account add card BYN");
        exec("income main 100 Зарплата");

        assertThat(exec("transfer main card 40")).containsExactly("Перевод выполнен: 40.00 BYN main -> card");
        assertThat(exec("account list")).containsExactly("main 60.00 BYN", "card 40.00 BYN");
    }

    @Test
    void balanceShowsIncomeMinusExpenses() {
        exec("account add main BYN");
        exec("income main 100 Зарплата");
        exec("expense main 25 Кафе");

        assertThat(exec("balance main")).containsExactly("main: 75.00 BYN");
    }

    @Test
    void reportCategoriesSumsExpensesOfMonthByCategory() {
        exec("account add main BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 200 Продукты");
        exec("expense main 50 Продукты");
        exec("expense main 300 Аренда");

        assertThat(exec("report categories 2026-10")).containsExactly(
                "Расходы по категориям за 2026-10:",
                "  Аренда: 300.00 BYN",
                "  Продукты: 250.00 BYN");
        assertThat(exec("report categories 2026-09")).containsExactly("Нет расходов за 2026-09");
    }

    @Test
    void reportTopShowsLargestExpenses() {
        exec("account add main BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 10 Кофе");
        exec("expense main 300 Аренда");
        exec("expense main 50 Кафе обед");

        assertThat(exec("report top 2")).containsExactly(
                "Топ-2 расходов:",
                "  1. 2026-10-15 main Аренда 300.00 BYN",
                "  2. 2026-10-15 main Кафе 50.00 BYN (обед)");
    }

    @Test
    void reportMonthlySumsIncomesAndExpensesIgnoringTransfers() {
        exec("account add main BYN");
        exec("account add card BYN");
        exec("income main 1000 Зарплата");
        exec("expense main 250 Продукты");
        exec("transfer main card 100");

        assertThat(exec("report monthly"))
                .containsExactly("2026-10 BYN: доходы 1000.00, расходы 250.00, итого 750.00");
    }

    @Test
    void helpListsCommands() {
        List<String> output = exec("help");

        assertThat(output.getFirst()).isEqualTo("Команды:");
        assertThat(output).anyMatch(line -> line.contains("report monthly"));
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class WithMockedOperationRepository {

        @Mock
        private OperationRepository mockedOperations;

        @Captor
        private ArgumentCaptor<Operation> saved;

        private final Account account = new Account(UUID.randomUUID(), "main", CurrencyCode.BYN);

        @Test
        void expenseSavesExactlyOneExpenseWithParsedFields() {
            accounts.saveAccount(account);
            when(mockedOperations.findAllOperations()).thenReturn(List.of(
                    new Income(UUID.randomUUID(), account.id(), new BigDecimal("100.00"),
                            "Зарплата", clock.instant(), null)));
            var mockedHandler = new CommandHandler(accounts, mockedOperations, new BalanceCalculator(), out, clock);

            mockedHandler.handle("expense main 30,5 Кафе обед с коллегами");

            verify(mockedOperations, times(1)).saveOperation(saved.capture());
            assertThat(saved.getValue())
                    .asInstanceOf(type(Expense.class))
                    .satisfies(expense -> assertThat(expense.id()).isNotNull())
                    .extracting(Expense::accountId, Expense::amount, Expense::category,
                            Expense::occurredAt, Expense::description)
                    .containsExactly(account.id(), new BigDecimal("30.50"), "Кафе",
                            clock.instant(), "обед с коллегами");
        }
    }
}
