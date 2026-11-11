# Индексы для аналитических запросов

Запросы лежат в `src/main/resources/db/queries/` (файлы `q01.sql`…`q15.sql`), индексы в миграции
`src/main/resources/db/migration/V3__indexes.sql`.

## Как мерили

Стенд: PostgreSQL 18.6 из `compose.yaml` и данные из `db/seed.sql`. Это 10 пользователей, 30 счетов
и 1 000 000 операций с 2025-01-01 по 2026-10-31 всего с шестью разными описаниями.
Таблица `operations` занимает 125 MB.

Перед замерами я выполнил `ANALYZE` всех таблиц. У `users` и `accounts` статистики не было
вообще: autoanalyze не срабатывает на 10 и 30 строках, и планировщик ошибался в оценках.
После `V3` выполнен `VACUUM ANALYZE operations`, иначе карта видимости не позволила бы
index-only scan.

Каждый запрос шёл под `EXPLAIN (ANALYZE, BUFFERS)` шесть раз. Первый прогон прогревал кэш,
в таблице указана медиана остальных пяти. Все страницы читались из кэша (`shared hit`),
так что измерено время CPU, диск в нём не участвует.

Параллелизм оставлен по умолчанию (`max_parallel_workers_per_gather = 2`), поэтому в планах
«до» встречаются `Parallel Seq Scan` и `Gather`.

## Все 15 запросов

| №   | Вопрос                                            | До, мс  | После, мс | Ускорение |
|-----|---------------------------------------------------|--------:|----------:|----------:|
| q01 | Баланс счетов на конец месяцев 2026               |   413   |   389     | ≈1×       |
| q02 | Топ-5 категорий расходов user1 за октябрь 2026    |    59.2 |     3.7   | 16×       |
| q03 | Расходы по месяцам за 12 месяцев с нулями         |   665   |   412     | 1.6×      |
| q04 | Нарастающий итог баланса счёта по дням            |    91.8 |    24.9   | 3.7×      |
| q05 | Ранг траты внутри категории                       |  2765   |  2731     | ≈1×       |
| q06 | Изменение расходов к прошлому месяцу              |  1259   |  1230     | ≈1×       |
| q07 | Пользователи без операций за 30 дней              |    10.1 |     0.36  | 28×       |
| q08 | Средний расход по дням недели                     |  1399   |  1409     | ≈1×       |
| q09 | Доля категории в расходах месяца                  |  1843   |  1955     | ≈1×       |
| q10 | Первые 3 операции каждого счёта                   |   758   |   791     | ≈1×       |
| q11 | Расхождение пересчитанного баланса с `balance`    |   782   |   774     | ≈1×       |
| q12 | Медиана расходов по категориям                    |  1549   |  1573     | ≈1×       |
| q13 | Скользящее среднее расходов за 7 дней             |   774   |   803     | ≈1×       |
| q14 | Операции счёта за период, первая страница         |    57.6 |     0.55  | 105×      |
| q15 | Поиск по подстроке описания                       |  1002   |   357     | 2.8×      |

«≈1×» значит, что план не изменился, а разница в пределах шума (5–10 %).

На свежем сиде q07 и q11 возвращают 0 строк, и для этих данных это правильный ответ.
У всех 10 пользователей есть операции в октябре 2026, а `seed.sql` выставляет `balance`
ровно по сумме операций. q11 я проверил на испорченном балансе
(`UPDATE accounts SET balance = balance + 1 ...` в транзакции с `ROLLBACK`): запрос нашёл этот счёт.

### Почему ускоряли не самые медленные по абсолютному времени

Дольше всех работают q05, q09, q12, q08 и q06, от 1.2 до 2.8 с. Все они агрегируют или
ранжируют все расходы сразу и читают 800 000 строк из миллиона. Для такого объёма
последовательное чтение и есть лучший способ доступа. Оно занимает 60–150 мс из 1.5–3 с,
остальное время уходит на сортировку и агрегацию 800 тысяч строк.

Я проверил это на индексе `(category_id, occurred_at) INCLUDE (amount, account_id, type)`:
для q05, q09 и q12 планировщик всё равно выбрал `Seq Scan`, время не изменилось.
Ускорить такие отчёты в 10 раз можно предагрегацией, например материализованным представлением
или таблицей месячных итогов.

Поэтому я отобрал запросы, у которых узкое место в поиске небольшой части строк среди миллиона:
q02, q04, q07, q14 и q15. В 10 раз и больше ускорились q02, q07 и q14, они разобраны ниже.
Почему q04 и q15 ускорились меньше, написано в отдельном разделе.

## q02. Топ-5 категорий расходов user1 за октябрь 2026: с 59.2 до 3.7 мс, 16×

До индекса запрос читает всю `operations` через `Parallel Seq Scan` (15 955 страниц),
фильтрует по периоду и типу и выбрасывает 963 тысячи строк из миллиона. Только потом
результат соединяется со счетами пользователя.

```
Limit  (cost=24438.69..24438.70 rows=5 width=59) (actual time=52.232..59.991 rows=5.00 loops=1)
  Buffers: shared hit=15997
  ->  Sort  (cost=24438.69..24438.73 rows=18 width=59) (actual time=52.229..59.987 rows=5.00 loops=1)
        Sort Key: (sum(o.amount)) DESC
        Sort Method: top-N heapsort  Memory: 25kB
        Buffers: shared hit=15997
        ->  Finalize GroupAggregate  (cost=24412.98..24438.39 rows=18 width=59) (actual time=51.691..59.900 rows=12.00 loops=1)
              Group Key: c.name, a.currency
              Buffers: shared hit=15994
              ->  Gather Merge  (cost=24412.98..24437.63 rows=43 width=59) (actual time=51.640..59.862 rows=36.00 loops=1)
                    Workers Planned: 2
                    Workers Launched: 2
                    Buffers: shared hit=15994
                    ->  Partial GroupAggregate  (cost=23412.95..23432.64 rows=18 width=59) (actual time=46.229..46.623 rows=12.00 loops=3)
                          Group Key: c.name, a.currency
                          Buffers: shared hit=15994
                          ->  Sort  (cost=23412.95..23416.85 rows=1557 width=25) (actual time=46.154..46.253 rows=1249.00 loops=3)
                                Sort Key: c.name, a.currency
                                Sort Method: quicksort  Memory: 112kB
                                Buffers: shared hit=15994
                                Worker 0:  Sort Method: quicksort  Memory: 98kB
                                Worker 1:  Sort Method: quicksort  Memory: 102kB
                                ->  Hash Join  (cost=3.79..23330.40 rows=1557 width=25) (actual time=0.199..45.313 rows=1249.00 loops=3)
                                      Hash Cond: (o.category_id = c.id)
                                      Buffers: shared hit=15964
                                      ->  Hash Join  (cost=2.59..23323.18 rows=1557 width=18) (actual time=0.138..44.895 rows=1249.00 loops=3)
                                            Hash Cond: (o.account_id = a.id)
                                            Buffers: shared hit=15961
                                            ->  Parallel Seq Scan on operations o  (cost=0.00..23246.67 rows=15565 width=30) (actual time=0.017..43.431 rows=12297.67 loops=3)
                                                  Filter: ((occurred_at >= '2026-10-01 00:00:00+00'::timestamp with time zone) AND (occurred_at < '2026-11-01 00:00:00+00'::timestamp with time zone) AND ((type)::text = 'EXPENSE'::text))
                                                  Rows Removed by Filter: 321036
                                                  Buffers: shared hit=15955
                                            ->  Hash  (cost=2.55..2.55 rows=3 width=20) (actual time=0.067..0.070 rows=3.00 loops=3)
                                                  Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                  Buffers: shared hit=6
                                                  ->  Hash Join  (cost=1.14..2.55 rows=3 width=20) (actual time=0.057..0.066 rows=3.00 loops=3)
                                                        Hash Cond: (a.user_id = u.id)
                                                        Buffers: shared hit=6
                                                        ->  Seq Scan on accounts a  (cost=0.00..1.30 rows=30 width=36) (actual time=0.014..0.017 rows=30.00 loops=3)
                                                              Buffers: shared hit=3
                                                        ->  Hash  (cost=1.12..1.12 rows=1 width=16) (actual time=0.020..0.022 rows=1.00 loops=3)
                                                              Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                                              Buffers: shared hit=3
                                                              ->  Seq Scan on users u  (cost=0.00..1.12 rows=1 width=16) (actual time=0.014..0.015 rows=1.00 loops=3)
                                                                    Filter: ((email)::text = 'user1@example.com'::text)
                                                                    Rows Removed by Filter: 9
                                                                    Buffers: shared hit=3
                                      ->  Hash  (cost=1.09..1.09 rows=9 width=23) (actual time=0.033..0.034 rows=9.00 loops=3)
                                            Buckets: 1024  Batches: 1  Memory Usage: 9kB
                                            Buffers: shared hit=3
                                            ->  Seq Scan on categories c  (cost=0.00..1.09 rows=9 width=23) (actual time=0.024..0.026 rows=9.00 loops=3)
                                                  Buffers: shared hit=3
Planning:
  Buffers: shared hit=498
Planning Time: 3.297 ms
Execution Time: 60.393 ms
```

С индексом три счёта user1 становятся внешним циклом nested loop, и для каждого счёта
выполняется `Index Only Scan` по диапазону `(account_id = ?, occurred_at в октябре)`.
Значения `type`, `amount` и `category_id` берутся из `INCLUDE`-части индекса, в таблицу запрос
не обращается (`Heap Fetches: 0`). Читается 48 страниц индекса против 15 955 страниц таблицы.

```
Limit  (cost=360.65..360.66 rows=5 width=59) (actual time=3.571..3.574 rows=5.00 loops=1)
  Buffers: shared hit=54
  ->  Sort  (cost=360.65..360.70 rows=18 width=59) (actual time=3.569..3.571 rows=5.00 loops=1)
        Sort Key: (sum(o.amount)) DESC
        Sort Method: top-N heapsort  Memory: 25kB
        Buffers: shared hit=54
        ->  HashAggregate  (cost=360.13..360.35 rows=18 width=59) (actual time=3.535..3.541 rows=12.00 loops=1)
              Group Key: c.name, a.currency
              Batches: 1  Memory Usage: 32kB
              Buffers: shared hit=51
              ->  Hash Join  (cost=1.63..323.44 rows=3669 width=25) (actual time=0.101..2.260 rows=3747.00 loops=1)
                    Hash Cond: (o.category_id = c.id)
                    Buffers: shared hit=51
                    ->  Nested Loop  (cost=0.42..308.07 rows=3669 width=18) (actual time=0.063..1.465 rows=3747.00 loops=1)
                          Buffers: shared hit=50
                          ->  Nested Loop  (cost=0.00..2.80 rows=3 width=20) (actual time=0.017..0.030 rows=3.00 loops=1)
                                Join Filter: (a.user_id = u.id)
                                Rows Removed by Join Filter: 27
                                Buffers: shared hit=2
                                ->  Seq Scan on users u  (cost=0.00..1.12 rows=1 width=16) (actual time=0.011..0.013 rows=1.00 loops=1)
                                      Filter: ((email)::text = 'user1@example.com'::text)
                                      Rows Removed by Filter: 9
                                      Buffers: shared hit=1
                                ->  Seq Scan on accounts a  (cost=0.00..1.30 rows=30 width=36) (actual time=0.005..0.008 rows=30.00 loops=1)
                                      Buffers: shared hit=1
                          ->  Index Only Scan using operations_account_id_occurred_at_idx on operations o  (cost=0.42..89.53 rows=1223 width=30) (actual time=0.023..0.342 rows=1249.00 loops=3)
                                Index Cond: ((account_id = a.id) AND (occurred_at >= '2026-10-01 00:00:00+00'::timestamp with time zone) AND (occurred_at < '2026-11-01 00:00:00+00'::timestamp with time zone))
                                Filter: ((type)::text = 'EXPENSE'::text)
                                Rows Removed by Filter: 302
                                Heap Fetches: 0
                                Index Searches: 3
                                Buffers: shared hit=48
                    ->  Hash  (cost=1.09..1.09 rows=9 width=23) (actual time=0.015..0.016 rows=9.00 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 9kB
                          Buffers: shared hit=1
                          ->  Seq Scan on categories c  (cost=0.00..1.09 rows=9 width=23) (actual time=0.006..0.008 rows=9.00 loops=1)
                                Buffers: shared hit=1
Planning:
  Buffers: shared hit=566
Planning Time: 1.717 ms
Execution Time: 3.706 ms
```

## q07. Пользователи без операций за последние 30 дней: с 10.1 до 0.36 мс, 28×

`NOT EXISTS` превращается в anti join, и до индекса его внутренняя сторона читает таблицу
через `Parallel Seq Scan` с фильтром `occurred_at >= now() - 30 days`. Быстро это работает
только потому, что операции в сиде перемешаны по времени и подходящие строки попадаются
на первых страницах. Большая часть из 10 мс уходит на запуск параллельных воркеров.
Будь у пользователя пусто за последний месяц, seq scan прочитал бы ради него всю таблицу.

```
Nested Loop Anti Join  (cost=1001.81..27442.85 rows=1 width=34) (actual time=1.044..9.976 rows=0.00 loops=1)
  Join Filter: (a.user_id = u.id)
  Rows Removed by Join Filter: 65
  Buffers: shared hit=26
  ->  Index Scan using users_email_key on users u  (cost=0.14..12.29 rows=10 width=34) (actual time=0.017..0.022 rows=10.00 loops=1)
        Index Searches: 1
        Buffers: shared hit=2
  ->  Materialize  (cost=1001.67..27427.61 rows=29923 width=16) (actual time=0.066..0.994 rows=7.50 loops=10)
        Storage: Memory  Maximum Storage: 17kB
        Buffers: shared hit=24
        ->  Gather  (cost=1001.67..27278.00 rows=29923 width=16) (actual time=0.653..9.923 rows=18.00 loops=1)
              Workers Planned: 2
              Workers Launched: 2
              Buffers: shared hit=24
              ->  Hash Join  (cost=1.68..23285.70 rows=12468 width=16) (actual time=0.074..0.186 rows=6.67 loops=3)
                    Hash Cond: (o.account_id = a.id)
                    Buffers: shared hit=24
                    ->  Parallel Seq Scan on operations o  (cost=0.00..23246.67 rows=12468 width=16) (actual time=0.024..0.134 rows=6.67 loops=3)
                          Filter: (occurred_at >= (now() - '30 days'::interval))
                          Rows Removed by Filter: 332
                          Buffers: shared hit=21
                    ->  Hash  (cost=1.30..1.30 rows=30 width=32) (actual time=0.028..0.028 rows=30.00 loops=3)
                          Buckets: 1024  Batches: 1  Memory Usage: 10kB
                          Buffers: shared hit=3
                          ->  Seq Scan on accounts a  (cost=0.00..1.30 rows=30 width=32) (actual time=0.015..0.018 rows=30.00 loops=3)
                                Buffers: shared hit=3
Planning:
  Buffers: shared hit=293
Planning Time: 1.135 ms
Execution Time: 10.058 ms
```

С индексом план становится nested loop anti join: для каждого пользователя берутся его счета
(`accounts_user_id_name_key`), а по каждому счёту выполняется `Index Only Scan` по
`(account_id = ?, occurred_at >= ?)`. Anti join останавливается на первой найденной записи,
поэтому на пользователя читается одна строка индекса (`rows=1, loops=10`). Время больше
не зависит от того, где в таблице лежат свежие операции.

```
Sort  (cost=188.49..188.50 rows=1 width=34) (actual time=0.249..0.249 rows=0.00 loops=1)
  Sort Key: u.email
  Sort Method: quicksort  Memory: 25kB
  Buffers: shared hit=55
  ->  Nested Loop Anti Join  (cost=0.57..188.48 rows=1 width=34) (actual time=0.225..0.225 rows=0.00 loops=1)
        Buffers: shared hit=52
        ->  Seq Scan on users u  (cost=0.00..1.10 rows=10 width=34) (actual time=0.006..0.007 rows=10.00 loops=1)
              Buffers: shared hit=1
        ->  Nested Loop  (cost=0.57..180.91 rows=2782 width=16) (actual time=0.021..0.021 rows=1.00 loops=10)
              Buffers: shared hit=51
              ->  Index Scan using accounts_user_id_name_key on accounts a  (cost=0.14..1.39 rows=3 width=32) (actual time=0.002..0.002 rows=1.00 loops=10)
                    Index Cond: (user_id = u.id)
                    Index Searches: 10
                    Buffers: shared hit=20
              ->  Index Only Scan using operations_account_id_occurred_at_idx on operations o  (cost=0.43..50.57 rows=927 width=16) (actual time=0.019..0.019 rows=1.00 loops=10)
                    Index Cond: ((account_id = a.id) AND (occurred_at >= (now() - '30 days'::interval)))
                    Heap Fetches: 0
                    Index Searches: 10
                    Buffers: shared hit=31
Planning:
  Buffers: shared hit=373
Planning Time: 1.185 ms
Execution Time: 0.322 ms
```

## q14. Операции счёта за период, новые сверху: с 57.6 до 0.55 мс, 105×

Запрос возвращает первую страницу выписки: `ORDER BY occurred_at DESC LIMIT 50`.
Следующие страницы берутся keyset-пагинацией (`AND occurred_at < <время последней строки>`).

До индекса `Parallel Seq Scan` проходит всю таблицу, отбирает 1 582 строки счёта за октябрь,
сортирует их и только после этого применяет `Limit 50`.

```
Limit  (cost=24277.49..24289.50 rows=50 width=69) (actual time=42.902..51.061 rows=50.00 loops=1)
  Buffers: shared hit=16032
  ->  Nested Loop Left Join  (cost=24277.49..24656.52 rows=1578 width=69) (actual time=42.901..51.056 rows=50.00 loops=1)
        Join Filter: (c.id = o.category_id)
        Rows Removed by Join Filter: 214
        Buffers: shared hit=16032
        ->  Gather Merge  (cost=24277.49..24461.28 rows=1578 width=62) (actual time=42.876..50.990 rows=50.00 loops=1)
              Workers Planned: 2
              Workers Launched: 2
              Buffers: shared hit=16031
              ->  Sort  (cost=23277.47..23279.11 rows=658 width=62) (actual time=38.682..38.711 rows=335.67 loops=3)
                    Sort Key: o.occurred_at DESC
                    Sort Method: quicksort  Memory: 72kB
                    Buffers: shared hit=16031
                    Worker 0:  Sort Method: quicksort  Memory: 62kB
                    Worker 1:  Sort Method: quicksort  Memory: 67kB
                    ->  Parallel Seq Scan on operations o  (cost=0.00..23246.67 rows=658 width=62) (actual time=0.090..38.320 rows=527.33 loops=3)
                          Filter: ((occurred_at >= '2026-10-01 00:00:00+00'::timestamp with time zone) AND (occurred_at < '2026-11-01 00:00:00+00'::timestamp with time zone) AND (account_id = 'c667b5d4-064a-1e5f-39f8-736c05428488'::uuid))
                          Rows Removed by Filter: 332806
                          Buffers: shared hit=15955
        ->  Materialize  (cost=0.00..1.14 rows=9 width=23) (actual time=0.000..0.001 rows=5.28 loops=50)
              Storage: Memory  Maximum Storage: 17kB
              Buffers: shared hit=1
              ->  Seq Scan on categories c  (cost=0.00..1.09 rows=9 width=23) (actual time=0.012..0.013 rows=9.00 loops=1)
                    Buffers: shared hit=1
Planning:
  Buffers: shared hit=295
Planning Time: 1.428 ms
Execution Time: 51.173 ms
```

С индексом выполняется `Index Scan` по `(account_id, occurred_at DESC)`. Строки приходят
уже отсортированными, узла `Sort` в плане нет, и `Limit` прекращает сканирование после 50 строк.
Читается 53 страницы вместо 15 955.

```
Limit  (cost=0.57..195.19 rows=50 width=69) (actual time=0.050..0.414 rows=50.00 loops=1)
  Buffers: shared hit=71
  ->  Nested Loop Left Join  (cost=0.57..6142.76 rows=1578 width=69) (actual time=0.049..0.408 rows=50.00 loops=1)
        Buffers: shared hit=71
        ->  Index Scan using operations_account_id_occurred_at_idx on operations o  (cost=0.42..6103.84 rows=1578 width=62) (actual time=0.031..0.346 rows=50.00 loops=1)
              Index Cond: ((account_id = 'c667b5d4-064a-1e5f-39f8-736c05428488'::uuid) AND (occurred_at >= '2026-10-01 00:00:00+00'::timestamp with time zone) AND (occurred_at < '2026-11-01 00:00:00+00'::timestamp with time zone))
              Index Searches: 1
              Buffers: shared hit=53
        ->  Memoize  (cost=0.15..0.17 rows=1 width=23) (actual time=0.001..0.001 rows=1.00 loops=50)
              Cache Key: o.category_id
              Cache Mode: logical
              Hits: 41  Misses: 9  Evictions: 0  Overflows: 0  Memory Usage: 2kB
              Buffers: shared hit=18
              ->  Index Scan using categories_pkey on categories c  (cost=0.14..0.16 rows=1 width=23) (actual time=0.002..0.002 rows=1.00 loops=9)
                    Index Cond: (id = o.category_id)
                    Index Searches: 9
                    Buffers: shared hit=18
Planning:
  Buffers: shared hit=337
Planning Time: 0.962 ms
Execution Time: 0.483 ms
```

Если убрать `LIMIT` и выбирать все 1 582 строки за месяц, тот же индекс даёт 6.5×
(с 54.8 до 8.5 мс). `Bitmap Heap Scan` всё равно читает около 1 500 страниц таблицы:
сид раскладывает операции одного счёта равномерно по всей таблице, примерно по две строки
на страницу. Порядок `occurred_at DESC` в индексе выбран как раз под постраничную выдачу.

## Ускорены меньше чем в 10 раз

### q04. Нарастающий итог по дням: с 91.8 до 24.9 мс, 3.7×

Доступ к данным индекс сделал почти бесплатным. `Index Only Scan` отдаёт 33 593 строки счёта
за 8 мс без обращений к таблице (`Heap Fetches: 0`), а вторая ветка, входящие переводы, проходит
`Index Scan` по частичному индексу за 0.02 мс. Оставшиеся ~15 мс занимают вычисление
`occurred_at::date` и `HashAggregate` по 33 тысячам строк. Это обработка уже нужных строк,
и индекс её не сократит.

### q15. Поиск по подстроке: с 1002 до 357 мс, 2.8×

GIN-индекс используется (`Bitmap Index Scan on operations_description_trgm_idx`, 30 мс),
но в сиде всего шесть разных описаний, и любая подстрока совпадает примерно с шестой частью
таблицы: `'%аптек%'` находит 167 039 строк. Bitmap heap scan всё равно читает все 15 954 страницы,
а сортировка 167 тысяч строк уходит на диск. Выигрыш только в том, что `ILIKE` перепроверяется
на 167 тысячах строк вместо миллиона.

На реальных описаниях, где тексты разные и подстроки редкие, trigram-индекс отдаёт десятки строк
и ускоряет поиск на порядки. Поэтому он остался в миграции.

## Индексы и порядок колонок

### `operations_account_id_occurred_at_idx`: `(account_id, occurred_at DESC) INCLUDE (type, amount, category_id)`

Нужен для q02, q04, q07 и q14, попутно ускоряет q03.

`account_id` стоит первым, потому что по нему всегда равенство (`account_id = ?`), а по
`occurred_at` идёт диапазон или сортировка. B-tree сужает поиск по ключу до первой колонки
с диапазоном включительно, поэтому при `(account_id, occurred_at)` поиск сразу попадает
в непрерывный отрезок «этот счёт, этот период». При обратном порядке `(occurred_at, account_id)`
первым был бы диапазон по времени. Для q14 пришлось бы пройти все операции октября всех
30 счетов, около 45 тысяч записей, и отбросить чужие, а для q04 с его полным периодом
пришлось бы пройти весь индекс.

`occurred_at` стоит вторым, и внутри одного счёта записи упорядочены по времени. Это даёт сразу
диапазон (`occurred_at >= ? AND < ?`) и готовый порядок для `ORDER BY occurred_at DESC`.
Узел `Sort` не нужен, а сканирование останавливается рано: на `LIMIT` в q14 и на первой строке
в `NOT EXISTS` в q07.

`DESC` соответствует основному сценарию «новые сверху». При сортировке по одной колонке B-tree
так же умеет идти в обратную сторону, так что `ASC` тоже работал бы. Направление станет важным,
если появится сортировка со смешанными направлениями вроде `ORDER BY occurred_at DESC, id ASC`.

`type`, `amount` и `category_id` вынесены в `INCLUDE`, потому что по ним не ищут и не сортируют.
Они нужны только как значения для index-only scan: в q02 тип, сумма и категория, в q04 тип и сумма,
плюс q07. В `INCLUDE` они хранятся только в листовых страницах, не раздувают внутренние уровни
дерева и не влияют на порядок ключа. Платим за это размером: индекс весит 64 MB при таблице
в 125 MB, зато запросы выше не читают таблицу.

### `operations_target_account_id_occurred_at_idx`: `(target_account_id, occurred_at DESC) WHERE target_account_id IS NOT NULL`

Нужен для второй ветки движений (входящих переводов) в q01, q04 и q11.

Без него ветка `WHERE target_account_id = ?` в q04 превращается в `Seq Scan` по миллиону строк,
и индекс по `account_id` вообще не ускорил бы запрос: всё время уходило бы на вторую ветку.

Порядок колонок выбран по той же причине, что и у первого индекса: сначала равенство по
`target_account_id`, затем диапазон и порядок по `occurred_at`, чтобы выписка входящих переводов
за период тоже шла по индексу.

Индекс частичный. `target_account_id` заполнен только у `TRANSFER`, это гарантирует `CHECK`,
так что остальные строки в индекс не попадают. Переводов в сиде пока нет, и индекс занимает одну
страницу (8 kB). Он же покрывает внешний ключ `target_account_id → accounts(id)` при удалении счёта.

### `operations_description_trgm_idx`: `GIN (description gin_trgm_ops)`

Нужен для q15. Требует расширения `pg_trgm`, оно создаётся в той же миграции.

Колонка одна, так что вопроса о порядке нет. B-tree здесь не подходит: у `ILIKE '%аптек%'`
ведущий `%`, и префикса для поиска в упорядоченном дереве нет.

GIN хранит для каждой триграммы (`'апт'`, `'пте'`, `'тек'`) список строк, где она встречается.
Поиск пересекает эти списки и перепроверяет кандидатов через `ILIKE`. Регистр не важен,
кириллица работает (локаль базы `en_US.utf8`).

Индекс весит 11 MB и замедляет вставки, потому что GIN обновляется на каждую операцию
(отчасти это сглаживает `fastupdate`). На текущем сиде эффект от него ограничен, подробности
в разделе про q15.

### Почему нет `(category_id, occurred_at)`

Ни один из 15 запросов не выбирает строки одной категории. q05, q09 и q12 группируют или
ранжируют все категории сразу и читают 80 % таблицы. Планировщик такой индекс не использует
(проверка описана выше), а индекс, который только замедляет вставки, держать незачем.
Он пригодится, когда появится запрос вида «расходы на кафе за период».
