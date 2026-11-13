# finance-console

[![CI](https://github.com/Silkfinik/finance-console/actions/workflows/ci.yml/badge.svg)](https://github.com/Silkfinik/finance-console/actions/workflows/ci.yml)

## Стек

- Java 25 (record-ы, sealed-интерфейсы, pattern matching в `switch`)
- Gradle 9.6 (Kotlin DSL, плагин `application`)
- Jackson 3.2: хранение данных в JSON
- PostgreSQL 18, JDBC + HikariCP, Flyway: хранилище в базе (включается отдельно)
- Redis 8, Lettuce: кэш балансов для хранилища в базе
- SLF4J + Logback: диагностический лог
- JUnit 6, AssertJ, Mockito, Testcontainers: тесты
- JaCoCo: покрытие, Spotless (palantir-java-format): форматирование. Исправить
  форматирование: `./gradlew spotlessApply`

## Запуск

```bash
./gradlew installDist
build/install/finance-console/bin/finance-console
```

Данные хранятся в `~/.finance-console/data.json`. Другой файл можно задать
переменной окружения `FINANCE_DATA`.

### Хранилище PostgreSQL

По умолчанию данные хранятся в JSON. Переменная `FINANCE_STORAGE=postgres`
включает хранилище в PostgreSQL:

```bash
docker compose up -d
FINANCE_STORAGE=postgres build/install/finance-console/bin/finance-console
```

Подключение задаётся переменными `FINANCE_DB_URL`, `FINANCE_DB_USER`,
`FINANCE_DB_PASSWORD`; по умолчанию `jdbc:postgresql://localhost:5432/finance`,
пользователь и пароль `finance`, как в `compose.yaml`. При запуске Flyway
применяет миграции из `src/main/resources/db/migration`. Счета принадлежат
локальному пользователю из миграции `V4__local_user.sql`.

Пул HikariCP: `maximumPoolSize = 10`, `connectionTimeout = 3000` мс. Расход
списывается одним условным `UPDATE ... WHERE id = ? AND balance >= ?` в той же
транзакции, что и вставка операции, поэтому одновременные расходы не уводят
баланс в минус.

### Кэш балансов в Redis

С хранилищем PostgreSQL команда `balance` читает баланс через
`CachedBalanceService` по схеме cache-aside: сначала ключ `balance:{accountId}`
в Redis, при промахе колонка `accounts.balance`, и значение кладётся в Redis
строкой с TTL 60 секунд. После commit дохода, расхода или перевода ключи
затронутых счетов удаляются. Адрес Redis задаётся переменной
`FINANCE_REDIS_URL`, по умолчанию `redis://localhost:6379`, как в
`compose.yaml`.

Если Redis недоступен, баланс читается из PostgreSQL, а ошибка пишется в лог
уровня WARN. Число попаданий и промахов кэша пишется в лог при выходе.

Диагностический лог пишется в `~/.finance-console/finance-console.log`, другой
файл можно задать переменной окружения `FINANCE_LOG`. В консоль выводится только
то, что предназначено пользователю.

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
разбирает операции через `switch` по типу операции без ветки `default`. Поэтому компилятор
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

### Пакетная вставка операций

`JdbcRepositoryTest.insertAllIsAtLeastFiveTimesFasterThanOneByOne` вставляет
10 000 доходов через `insertAll` (одна транзакция, `addBatch` пачками по 500,
драйвер с `reWriteBatchedInserts=true`) и ещё 10 000 по одной через
`saveOperation` (на каждую операцию своя транзакция: `UPDATE` баланса, поиск
категории, `INSERT`, `COMMIT`). Перед замером прогрев. Postgres 18 в
Testcontainers, локальный Docker:

| Способ                   | Время, мс |
|--------------------------|----------:|
| `insertAll`              |       446 |
| `saveOperation` по одной |    12 149 |

`insertAll` быстрее в 27 раз. Тест падает, если разница меньше 5 раз.
