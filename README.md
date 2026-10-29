# finance-console

## Цель

## Стек

## Возможности (план)

## Запуск

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

## Долги
