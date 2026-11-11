# Аномалии параллельных транзакций и уровни изоляции

Четыре аномалии (non-repeatable read, lost update, phantom read, write skew), воспроизведённые
в двух параллельных сеансах psql A и B, и способы устранить каждую. Выводы команд скопированы
из реального прогона.

## Стенд

PostgreSQL 18.6 из `compose.yaml`, уровень по умолчанию `READ COMMITTED`.

Чтобы не трогать сид-данные, я завёл отдельную схему `iso` с копиями `accounts` и `operations`.
`LIKE ... INCLUDING DEFAULTS INCLUDING INDEXES` переносит колонки, умолчания, PK и UNIQUE,
но не CHECK и не внешние ключи. Для write skew это и нужно: в боевой таблице
`CHECK (balance >= 0)` не даёт отдельному счёту уйти в минус, а правило «сумма двух счетов
не меньше 0» живёт в приложении, и база его не проверяет.

```sql
CREATE SCHEMA iso;
CREATE TABLE iso.accounts   (LIKE public.accounts   INCLUDING DEFAULTS INCLUDING INDEXES);
CREATE TABLE iso.operations (LIKE public.operations INCLUDING DEFAULTS INCLUDING INDEXES);

INSERT INTO iso.accounts (id, user_id, name, currency, balance) VALUES
  ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-0000000000f1', 'Карта',    'BYN', 1000.00),
  ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-0000000000f1', 'Наличные', 'BYN',  100.00),
  ('00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-0000000000f1', 'Запас',    'BYN',  100.00);

-- три операции по 10.00 на счёте «Карта», для сценария с фантомом
INSERT INTO iso.operations (id, account_id, type, amount, category_id, occurred_at)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-00000000000a', 'EXPENSE', 10.00, 1, now()
FROM generate_series(1, 3);
```

В начале каждого сеанса:

```sql
SET search_path = iso;
\set id '00000000-0000-0000-0000-00000000000a'
\set b  '00000000-0000-0000-0000-00000000000b'
\set c  '00000000-0000-0000-0000-00000000000c'
```

Перед каждым сценарием баланс «Карты» возвращается к 1000.00, а «Наличных» и «Запаса» к 100.00.
Команды выполняются строго в указанном порядке, результат каждого `SELECT` записан комментарием.

## 1. Non-repeatable read

Транзакция читает одну и ту же строку дважды и получает разные значения, потому что
между чтениями другая транзакция изменила строку и закоммитила.

### Воспроизведение: READ COMMITTED

```sql
A> BEGIN ISOLATION LEVEL READ COMMITTED;
A> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
B> BEGIN;
B> UPDATE accounts SET balance = balance - 100 WHERE id = :'id';
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';          --  900.00  ← то же чтение, другой ответ
A> COMMIT;
```

На `READ COMMITTED` каждый оператор берёт свой снимок данных. Второй `SELECT` в A начался
после коммита B и видит новую версию строки.

### Исправление: REPEATABLE READ

```sql
A> BEGIN ISOLATION LEVEL REPEATABLE READ;
A> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
B> BEGIN;
B> UPDATE accounts SET balance = balance - 100 WHERE id = :'id';
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00  ← повторное чтение совпало
A> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';          --  900.00  (после конца транзакции видно изменение B)
```

На `REPEATABLE READ` снимок берётся один раз, на первом операторе транзакции, и держится
до её конца. B при этом не блокируется: читатель не мешает писателю.

| | 1-е чтение A | 2-е чтение A |
|---|---:|---:|
| READ COMMITTED | 1000.00 | 900.00 |
| REPEATABLE READ | 1000.00 | 1000.00 |

## 2. Lost update

Две транзакции читают баланс, каждая считает новое значение в приложении и записывает его.
Запись второй затирает запись первой. A списывает 100, B списывает 200, правильный итог 700.00.

### Воспроизведение: READ COMMITTED

```sql
A> BEGIN;
A> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
B> BEGIN;
B> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
A> UPDATE accounts SET balance = 900.00 WHERE id = :'id';  -- 1000 − 100
A> COMMIT;
B> UPDATE accounts SET balance = 800.00 WHERE id = :'id';  -- 1000 − 200, по устаревшему чтению
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';          --  800.00  ← списание A потеряно
```

### Исправление 1: SELECT … FOR UPDATE

Пессимистическая блокировка: чтение сразу захватывает строку, и второй читатель ждёт.

```sql
A> BEGIN;
A> SELECT balance FROM accounts WHERE id = :'id' FOR UPDATE;   -- 1000.00
B> BEGIN;
B> SELECT balance FROM accounts WHERE id = :'id' FOR UPDATE;   -- … B висит на блокировке строки
A> UPDATE accounts SET balance = 900.00 WHERE id = :'id';
A> COMMIT;
                                                               -- B проснулся: 900.00
B> UPDATE accounts SET balance = 700.00 WHERE id = :'id';      -- 900 − 200
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';              --  700.00  ✓
```

B получил строку только после коммита A и уже видит 900.00. Цена этого способа в ожидании:
пока A держит строку, все остальные желающие её изменить стоят в очереди.

### Исправление 2: атомарный UPDATE

Чтение и запись в одном операторе, приложение ничего не считает.

```sql
A> BEGIN;
B> BEGIN;
A> UPDATE accounts SET balance = balance - 100 WHERE id = :'id';
B> UPDATE accounts SET balance = balance - 200 WHERE id = :'id';   -- … B висит на блокировке строки
A> COMMIT;
                                                                   -- B проснулся и выполнил UPDATE
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';                  --  700.00  ✓
```

Здесь работает особенность `READ COMMITTED`: когда UPDATE в B дождался строки, PostgreSQL перечитывает
её последнюю закоммиченную версию (900.00) и заново вычисляет `balance - 200` уже от неё.
Поэтому `SET balance = balance - 200` безопасен, а `SET balance = 800.00` нет.

### Исправление 3: REPEATABLE READ

```sql
A> BEGIN ISOLATION LEVEL REPEATABLE READ;
A> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
B> BEGIN ISOLATION LEVEL REPEATABLE READ;
B> SELECT balance FROM accounts WHERE id = :'id';          -- 1000.00
A> UPDATE accounts SET balance = 900.00 WHERE id = :'id';
A> COMMIT;
B> UPDATE accounts SET balance = 800.00 WHERE id = :'id';
-- ERROR:  could not serialize access due to concurrent update
B> ROLLBACK;

-- повтор транзакции B целиком
B> BEGIN ISOLATION LEVEL REPEATABLE READ;
B> SELECT balance FROM accounts WHERE id = :'id';          --  900.00
B> UPDATE accounts SET balance = 700.00 WHERE id = :'id';
B> COMMIT;
A> SELECT balance FROM accounts WHERE id = :'id';          --  700.00  ✓
```

На `REPEATABLE READ` транзакция не имеет права изменить строку, которую после начала её снимка
изменил и закоммитил кто-то другой (правило «первый записавший побеждает»). Перечитывания,
как на `READ COMMITTED`, здесь нет: B сразу отменяется с `SQLSTATE 40001`. Пользователь увидит
эту ошибку, если приложение не повторит транзакцию само, поэтому код на этом уровне должен
уметь повторять её целиком, начиная с чтения.

| Способ | Что делает B | Итог |
|---|---|---:|
| без защиты, READ COMMITTED | пишет 800.00 по устаревшему чтению | 800.00 ✗ |
| `SELECT … FOR UPDATE` | ждёт A, читает 900.00, пишет 700.00 | 700.00 ✓ |
| `SET balance = balance - 200` | ждёт A, UPDATE пересчитывается от 900.00 | 700.00 ✓ |
| REPEATABLE READ | получает ошибку 40001, повторяет транзакцию | 700.00 ✓ |

## 3. Phantom read

Транзакция дважды выполняет запрос с одним и тем же условием и во второй раз видит новые
строки, которые другая транзакция вставила и закоммитила между запросами. В отличие от
non-repeatable read, существующие строки не меняются, появляются новые.

### Воспроизведение: READ COMMITTED

```sql
A> BEGIN ISOLATION LEVEL READ COMMITTED;
A> SELECT count(*), sum(amount) FROM operations WHERE account_id = :'id';   -- 3 | 30.00
B> BEGIN;
B> INSERT INTO operations (id, account_id, type, amount, category_id, description, occurred_at)
   VALUES (gen_random_uuid(), :'id', 'EXPENSE', 50.00, 1, 'phantom', now());
B> COMMIT;
A> SELECT count(*), sum(amount) FROM operations WHERE account_id = :'id';   -- 4 | 80.00  ← фантом
A> COMMIT;
```

### Проверка: REPEATABLE READ

```sql
A> BEGIN ISOLATION LEVEL REPEATABLE READ;
A> SELECT count(*), sum(amount) FROM operations WHERE account_id = :'id';   -- 3 | 30.00
B> BEGIN;
B> INSERT INTO operations (id, account_id, type, amount, category_id, description, occurred_at)
   VALUES (gen_random_uuid(), :'id', 'EXPENSE', 50.00, 1, 'phantom', now());
B> COMMIT;
A> SELECT count(*), sum(amount) FROM operations WHERE account_id = :'id';   -- 3 | 30.00  ← фантома нет
A> COMMIT;
A> SELECT count(*), sum(amount) FROM operations WHERE account_id = :'id';   -- 4 | 80.00  (вне транзакции)
```

| | 1-й подсчёт A | 2-й подсчёт A |
|---|---:|---:|
| READ COMMITTED | 3 / 30.00 | 4 / 80.00 |
| REPEATABLE READ | 3 / 30.00 | 3 / 30.00 |

### Почему в PostgreSQL на REPEATABLE READ нет фантомов, а стандарт их допускает

Стандарт SQL определяет уровни изоляции через запрещённые явления:

| Уровень | Dirty read | Non-repeatable read | Phantom |
|---|---|---|---|
| READ UNCOMMITTED | можно | можно | можно |
| READ COMMITTED | нельзя | можно | можно |
| REPEATABLE READ | нельзя | нельзя | можно |
| SERIALIZABLE | нельзя | нельзя | нельзя |

Таблица писалась под реализацию на блокировках. В такой СУБД на `REPEATABLE READ` каждая
прочитанная строка блокируется до конца транзакции. Прочитанные строки никто не изменит,
и non-repeatable read исчезает. Заблокировать можно только существующую строку, а той, которую
вставят позже, в момент чтения ещё нет. Поэтому чужой `INSERT` под то же условие проходит,
и повторный запрос его видит. Чтобы закрыть
фантомы, нужно блокировать само условие (предикатные блокировки или блокировки диапазонов
индекса), а это уже уровень `SERIALIZABLE`. Стандарт описывает минимальные требования:
уровень обязан запрещать то, что указано в его строке, и может запрещать больше.

PostgreSQL реализует `REPEATABLE READ` без блокировок чтения, через MVCC и изоляцию снимков
(snapshot isolation):

- на первом операторе транзакция получает снимок: список транзакций, закоммиченных к этому
  моменту;
- у каждой версии строки есть `xmin`, номер транзакции, которая её создала;
- запрос видит версию, только если `xmin` закоммичен в снимке.

Строка, вставленная транзакцией B, имеет `xmin` = номер B. B закоммитилась после того, как A
взяла снимок, значит для A этой транзакции «ещё не было», и строка невидима. Это
верно для любого запроса в транзакции A с любым условием: чужие изменения и чужие новые строки,
закоммиченные после снимка, в нём не появляются. Фантомы исключены самой моделью MVCC, условие
блокировать не нужно. Стандарт это разрешает, потому что уровень может запрещать больше
минимума.

При этом snapshot isolation слабее `SERIALIZABLE`. Транзакции, которые пишут в разные строки,
не конфликтуют друг с другом, и на этом построен write skew из раздела 4.

## 4. Write skew

Правило: сумма балансов счетов «Наличные» и «Запас» не меньше 0. Обе транзакции проверяют
правило, обе видят 200.00 и обе решают, что списать 150 можно: 200 − 150 = 50 ≥ 0. A списывает
с «Наличных», B с «Запаса». Каждая пишет свою строку, конфликта записи нет.

### Воспроизведение: REPEATABLE READ

```sql
A> BEGIN ISOLATION LEVEL REPEATABLE READ;
A> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   -- 200.00  → 200 − 150 ≥ 0, можно
B> BEGIN ISOLATION LEVEL REPEATABLE READ;
B> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   -- 200.00  → 200 − 150 ≥ 0, можно
A> UPDATE accounts SET balance = balance - 150 WHERE id = :'b';
B> UPDATE accounts SET balance = balance - 150 WHERE id = :'c';
A> COMMIT;
B> COMMIT;
A> SELECT name, balance FROM accounts WHERE id IN (:'b', :'c') ORDER BY name;
--  Запас    | -50.00
--  Наличные | -50.00
A> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   -- -100.00  ← правило нарушено
```

Ошибки нет: A и B меняли разные строки, и правило «первый записавший побеждает» не сработало.
Каждая транзакция по отдельности корректна, но вместе они дают результат, который не мог бы
получиться ни при одном последовательном порядке: при любом порядке вторая увидела бы 50.00
и отказалась.

### Исправление: SERIALIZABLE

```sql
A> BEGIN ISOLATION LEVEL SERIALIZABLE;
A> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   -- 200.00
B> BEGIN ISOLATION LEVEL SERIALIZABLE;
B> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   -- 200.00
A> UPDATE accounts SET balance = balance - 150 WHERE id = :'b';
B> UPDATE accounts SET balance = balance - 150 WHERE id = :'c';
A> COMMIT;
B> COMMIT;
-- ERROR:  could not serialize access due to read/write dependencies among transactions
A> SELECT name, balance FROM accounts WHERE id IN (:'b', :'c') ORDER BY name;
--  Запас    | 100.00
--  Наличные | -50.00
A> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   --  50.00  ✓

-- повтор транзакции B целиком
B> BEGIN ISOLATION LEVEL SERIALIZABLE;
B> SELECT sum(balance) FROM accounts WHERE id IN (:'b', :'c');   --  50.00  → 50 − 150 < 0, отказ
B> ROLLBACK;
```

На `SERIALIZABLE` PostgreSQL использует Serializable Snapshot Isolation: тот же снимок, что
и на `REPEATABLE READ`, плюс неблокирующие предикатные блокировки (`SIReadLock`), которые
запоминают, что читала каждая транзакция. A прочитала строку `c`, а B её изменила. B
прочитала строку `b`, а A её изменила. Получился цикл зависимостей чтение→запись, который
невозможен ни при одном последовательном порядке, и одна из транзакций отменяется с `40001`.
Здесь первой закоммитилась A, поэтому отменилась B, на коммите. Отказ может прийти и раньше,
на `UPDATE`, это зависит от момента, когда цикл обнаружен.

Так же, как в исправлении 3 для lost update, приложение обязано повторять транзакцию. На
повторе B видит 50.00 и по правилу отказывает в списании. Сумма осталась неотрицательной.

| | Итог: Наличные / Запас | Сумма |
|---|---|---:|
| REPEATABLE READ | -50.00 / -50.00 | -100.00 ✗ |
| SERIALIZABLE | -50.00 / 100.00, B отменена | 50.00 ✓ |

На `REPEATABLE READ` эту аномалию тоже можно закрыть вручную: `SELECT … FOR UPDATE` на обе
строки при проверке правила. Тогда B ждёт A на блокировке и после коммита A получает ошибку
сериализации на `REPEATABLE READ` (или видит свежие данные на `READ COMMITTED`). Это работает,
пока известно, какие строки блокировать. Если правило зависит от строк, которых ещё нет
(например, «не больше N операций в день»), блокировать нечего, и остаётся `SERIALIZABLE`.

## Сводка

| Аномалия | READ COMMITTED | REPEATABLE READ (PostgreSQL) | SERIALIZABLE |
|---|---|---|---|
| Non-repeatable read | есть | нет | нет |
| Lost update | есть (если считать в приложении) | нет, ошибка 40001 | нет, ошибка 40001 |
| Phantom read | есть | нет | нет |
| Write skew | есть | есть | нет, ошибка 40001 |

Ошибкой 40001 на `REPEATABLE READ` и `SERIALIZABLE` база отказывается выполнить транзакцию,
которая испортила бы данные. Код, использующий эти уровни, должен ловить `40001` и повторять
транзакцию с начала.
