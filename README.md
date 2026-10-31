# finance-console

## Стек

- Java 25 (record-ы, sealed-интерфейсы, pattern matching в `switch`)
- Gradle 9.6 (Kotlin DSL, плагин `application`)
- Jackson 3.2: хранение данных в JSON
- JUnit 6: тесты

## Запуск

```bash
./gradlew installDist
build/install/finance-console/bin/finance-console
```

Данные хранятся в `~/.finance-console/data.json`. Другой файл можно задать
переменной окружения `FINANCE_DATA`.

### Пример сеанса

Сеанс запущен на чистом файле данных, поэтому повторяется один в один:

```bash
rm -f /tmp/finance-demo.json
FINANCE_DATA=/tmp/finance-demo.json build/install/finance-console/bin/finance-console
```

```
Finance Console. Введите help для списка команд.
> account add main BYN
Счёт создан: main (BYN)
> account add savings BYN
Счёт создан: savings (BYN)
> income main 1500 Зарплата за октябрь
Доход записан: 1500.00 BYN
> expense main 200 Продукты
Расход записан: 200.00 BYN
> expense main 2000 Техника
Ошибка: Недостаточно средств
> transfer main savings 500
Перевод выполнен: 500.00 BYN main -> savings
> balance main
main: 800.00 BYN
> balance card
Ошибка: Нет счёта: card
> account list
main 800.00 BYN
savings 500.00 BYN
> exit
```

Отчёты (`report …`) в пример не вошли: их вывод зависит от текущей даты.

## Проверки

### Исчерпывающий switch в `BalanceCalculator`

`Operation` - sealed-интерфейс (`permits Income, Expense, Transfer`), а `BalanceCalculator`
разбирает операции через `switch` с record patterns без ветки `default`. Поэтому компилятор
сам следит, что учтены все виды операций.

Проверено: временно добавлена четвёртая реализация
`record Refund(UUID id, UUID accountId, BigDecimal amount, Instant occurredAt) implements Operation`
и `Refund` вписан в `permits`. Сборка упала:

```
BalanceCalculator.java:20: error: the switch expression does not cover all possible input values
        return switch (op) {
               ^
1 error
```

Вывод: новую операцию невозможно добавить, не обработав её в расчёте баланса.