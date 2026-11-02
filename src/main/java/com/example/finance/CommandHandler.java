package com.example.finance;

import com.example.finance.domain.Account;
import com.example.finance.domain.BalanceCalculator;
import com.example.finance.domain.CurrencyCode;
import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import com.example.finance.report.ReportService;
import com.example.finance.storage.AccountRepository;
import com.example.finance.storage.OperationRepository;
import com.example.finance.storage.StorageException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    static final String INSUFFICIENT_FUNDS = "Недостаточно средств";

    private static final String HELP = """
            Команды:
              account add <name> <BYN|USD|EUR>                     создать счёт
              account list                                         список счетов с балансами
              income <account> <amount> <category> [description]   доход
              expense <account> <amount> <category> [description]  расход
              transfer <fromAccount> <toAccount> <amount>          перевод между счетами
              balance <account>                                    баланс счёта
              report categories <yyyy-MM>                          расходы по категориям за месяц
              report top <n>                                       n самых крупных расходов
              report monthly                                       доходы и расходы по месяцам
              help                                                 эта справка
              exit                                                 выход""";

    private final AccountRepository accounts;
    private final OperationRepository operations;
    private final BalanceCalculator balanceCalculator;
    private final ConsolePrinter out;
    private final Clock clock;
    private final ReportService reports;

    public CommandHandler(
            AccountRepository accounts,
            OperationRepository operations,
            BalanceCalculator balanceCalculator,
            ConsolePrinter out,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts не может быть null");
        this.operations = Objects.requireNonNull(operations, "operations не может быть null");
        this.balanceCalculator = Objects.requireNonNull(balanceCalculator, "balanceCalculator не может быть null");
        this.out = Objects.requireNonNull(out, "out не может быть null");
        this.clock = Objects.requireNonNull(clock, "clock не может быть null");
        this.reports = new ReportService(clock.getZone());
    }

    public boolean handle(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return true;
        }
        log.debug("Команда: {}", trimmed);
        try {
            return dispatch(trimmed.split("\\s+"));
        } catch (InputException | IllegalArgumentException e) {
            log.debug("Команда отклонена: {}", e.getMessage());
            out.println("Ошибка: " + e.getMessage());
        } catch (StorageException e) {
            log.error("Ошибка хранилища при выполнении команды: {}", trimmed, e);
            out.println("Ошибка хранилища: " + e.getMessage());
        }
        return true;
    }

    private boolean dispatch(String[] tokens) {
        String command = tokens[0];
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        switch (command) {
            case "account" ->
                dispatchSubcommand(
                        command,
                        args,
                        Map.of(
                                "add", this::accountAdd,
                                "list", this::accountList));
            case "report" ->
                dispatchSubcommand(
                        command,
                        args,
                        Map.of(
                                "categories", this::reportCategories,
                                "top", this::reportTop,
                                "monthly", this::reportMonthly));
            case "income" -> income(args);
            case "expense" -> expense(args);
            case "transfer" -> transfer(args);
            case "balance" -> balance(args);
            case "help" -> help(args);
            case "exit" -> {
                exit(args);
                return false;
            }
            default -> throw new InputException("Неизвестная команда: " + command + ". Введите help");
        }
        return true;
    }

    private static void dispatchSubcommand(String command, String[] args, Map<String, Consumer<String[]>> handlers) {
        if (args.length == 0) {
            throw new InputException("Не указана подкоманда: " + command + " "
                    + handlers.keySet().stream().sorted().collect(Collectors.joining("|")));
        }
        Consumer<String[]> handler = handlers.get(args[0]);
        if (handler == null) {
            throw new InputException("Неизвестная команда: " + command + " " + args[0] + ". Введите help");
        }
        handler.accept(Arrays.copyOfRange(args, 1, args.length));
    }

    // Commands

    private void accountAdd(String[] args) {
        requireArgCount(args, 2, 2, "account add <name> <BYN|USD|EUR>");
        String name = args[0];
        CurrencyCode currency = parseCurrency(args[1]);
        if (findAccount(name) != null) {
            throw new InputException("Счёт уже существует: " + name);
        }
        accounts.saveAccount(new Account(UUID.randomUUID(), name, currency));
        out.println("Счёт создан: " + name + " (" + currency + ")");
    }

    private void accountList(String[] args) {
        requireArgCount(args, 0, 0, "account list");
        List<Account> all = accounts.findAllAccounts();
        if (all.isEmpty()) {
            out.println("Счетов нет");
            return;
        }
        List<Operation> ops = operations.findAllOperations();
        for (Account account : all) {
            BigDecimal balance = balanceCalculator.balanceOf(account.id(), ops);
            out.println(account.name() + " " + format(balance) + " " + account.currency());
        }
    }

    private void income(String[] args) {
        requireArgCount(args, 3, Integer.MAX_VALUE, "income <account> <amount> <category> [description]");
        Account account = requireAccount(args[0]);
        BigDecimal amount = parseAmount(args[1]);
        var income = new Income(UUID.randomUUID(), account.id(), amount, args[2], now(), description(args));
        operations.saveOperation(income);
        out.println("Доход записан: " + format(income.amount()) + " " + account.currency());
    }

    private void expense(String[] args) {
        requireArgCount(args, 3, Integer.MAX_VALUE, "expense <account> <amount> <category> [description]");
        Account account = requireAccount(args[0]);
        BigDecimal amount = parseAmount(args[1]);
        var expense = new Expense(UUID.randomUUID(), account.id(), amount, args[2], now(), description(args));
        requireSufficientFunds(account, expense.amount());
        operations.saveOperation(expense);
        out.println("Расход записан: " + format(expense.amount()) + " " + account.currency());
    }

    private void transfer(String[] args) {
        requireArgCount(args, 3, 3, "transfer <fromAccount> <toAccount> <amount>");
        Account from = requireAccount(args[0]);
        Account to = requireAccount(args[1]);
        BigDecimal amount = parseAmount(args[2]);
        if (from.id().equals(to.id())) {
            throw new InputException("Нельзя перевести деньги на тот же счёт: " + from.name());
        }
        if (from.currency() != to.currency()) {
            throw new InputException("Перевод между счетами в разных валютах не поддерживается: " + from.currency()
                    + " -> " + to.currency());
        }
        var transfer = new Transfer(UUID.randomUUID(), from.id(), to.id(), amount, now());
        requireSufficientFunds(from, transfer.amount());
        operations.saveOperation(transfer);
        out.println("Перевод выполнен: " + format(transfer.amount()) + " " + from.currency() + " " + from.name()
                + " -> " + to.name());
    }

    private void balance(String[] args) {
        requireArgCount(args, 1, 1, "balance <account>");
        Account account = requireAccount(args[0]);
        BigDecimal balance = balanceCalculator.balanceOf(account.id(), operations.findAllOperations());
        out.println(account.name() + ": " + format(balance) + " " + account.currency());
    }

    private void reportCategories(String[] args) {
        requireArgCount(args, 1, 1, "report categories <yyyy-MM>");
        YearMonth month = parseMonth(args[0]);
        Map<CurrencyCode, Map<String, BigDecimal>> totals = new TreeMap<>();
        operationsByCurrency().forEach((currency, ops) -> {
            Map<String, BigDecimal> byCategory = reports.expensesByCategory(ops, month);
            if (!byCategory.isEmpty()) {
                totals.put(currency, byCategory);
            }
        });
        if (totals.isEmpty()) {
            out.println("Нет расходов за " + month);
            return;
        }
        out.println("Расходы по категориям за " + month + ":");
        totals.forEach((currency, byCategory) -> byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(entry ->
                        out.println("  " + entry.getKey() + ": " + format(entry.getValue()) + " " + currency)));
    }

    private void reportTop(String[] args) {
        requireArgCount(args, 1, 1, "report top <n>");
        int n = parsePositiveInt(args[0]);
        Map<UUID, Account> byId = accountsById();
        List<Expense> top = reports.topExpenses(knownOperations(byId), n);
        if (top.isEmpty()) {
            out.println("Расходов нет");
            return;
        }
        out.println("Топ-" + n + " расходов:");
        for (int i = 0; i < top.size(); i++) {
            Expense e = top.get(i);
            Account account = byId.get(e.accountId());
            String line = "  " + (i + 1) + ". " + LocalDate.ofInstant(e.occurredAt(), clock.getZone())
                    + " " + account.name() + " " + e.category()
                    + " " + format(e.amount()) + " " + account.currency();
            out.println(e.description() == null ? line : line + " (" + e.description() + ")");
        }
    }

    private void reportMonthly(String[] args) {
        requireArgCount(args, 0, 0, "report monthly");
        Map<YearMonth, Map<CurrencyCode, String>> lines = new TreeMap<>();
        operationsByCurrency().forEach((currency, ops) -> {
            Map<YearMonth, BigDecimal> incomes = reports.monthlyNet(only(ops, Income.class));
            Map<YearMonth, BigDecimal> expenses = reports.monthlyNet(only(ops, Expense.class));
            reports.monthlyNet(ops)
                    .forEach((month, net) -> lines.computeIfAbsent(month, _ -> new TreeMap<>())
                            .put(
                                    currency,
                                    month + " " + currency
                                            + ": доходы " + format(incomes.getOrDefault(month, BigDecimal.ZERO))
                                            + ", расходы "
                                            + format(expenses.getOrDefault(month, BigDecimal.ZERO)
                                                    .negate())
                                            + ", итого " + format(net)));
        });
        if (lines.isEmpty()) {
            out.println("Нет доходов и расходов");
            return;
        }
        lines.values().forEach(byCurrency -> byCurrency.values().forEach(out::println));
    }

    private void help(String[] args) {
        requireArgCount(args, 0, 0, "help");
        out.println(HELP);
    }

    private void exit(String[] args) {
        requireArgCount(args, 0, 0, "exit");
    }

    // Parsing and validation

    private static void requireArgCount(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            throw new InputException("Неверные аргументы. Использование: " + usage);
        }
    }

    private static BigDecimal parseAmount(String raw) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new InputException("Неверная сумма: " + raw);
        }
        if (amount.signum() <= 0) {
            throw new InputException("Неверная сумма: " + raw + ", сумма должна быть больше 0");
        }
        return amount;
    }

    private static CurrencyCode parseCurrency(String raw) {
        try {
            return CurrencyCode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InputException("Неизвестная валюта: " + raw + ", допустимо: "
                    + Arrays.stream(CurrencyCode.values()).map(Enum::name).collect(Collectors.joining(", ")));
        }
    }

    private static YearMonth parseMonth(String raw) {
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            throw new InputException("Неверный месяц: " + raw + ", ожидается yyyy-MM");
        }
    }

    private static int parsePositiveInt(String raw) {
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n <= 0) {
            throw new InputException("Неверное число: " + raw + ", ожидается целое больше 0");
        }
        return n;
    }

    private Account requireAccount(String name) {
        Account account = findAccount(name);
        if (account == null) {
            throw new InputException("Нет счёта: " + name);
        }
        return account;
    }

    private void requireSufficientFunds(Account account, BigDecimal amount) {
        BigDecimal balance = balanceCalculator.balanceOf(account.id(), operations.findAllOperations());
        if (balance.compareTo(amount) < 0) {
            throw new InputException(INSUFFICIENT_FUNDS);
        }
    }

    // Helpers

    private Account findAccount(String name) {
        return accounts.findAllAccounts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Map<UUID, Account> accountsById() {
        return accounts.findAllAccounts().stream().collect(Collectors.toMap(Account::id, Function.identity()));
    }

    private List<Operation> knownOperations(Map<UUID, Account> byId) {
        return operations.findAllOperations().stream()
                .filter(op -> byId.containsKey(op.accountId()))
                .toList();
    }

    private Map<CurrencyCode, List<Operation>> operationsByCurrency() {
        Map<UUID, Account> byId = accountsById();
        return knownOperations(byId).stream()
                .collect(Collectors.groupingBy(
                        op -> byId.get(op.accountId()).currency(), TreeMap::new, Collectors.toList()));
    }

    private static List<Operation> only(List<Operation> ops, Class<? extends Operation> type) {
        return ops.stream().filter(type::isInstance).toList();
    }

    private static String description(String[] args) {
        return args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
    }

    private Instant now() {
        return clock.instant();
    }

    private static String format(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }
}
