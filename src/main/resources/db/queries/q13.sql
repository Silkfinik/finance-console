-- Скользящее среднее расходов за 7 дней.
-- Пустые дни добиваем нулями, иначе ROWS 6 PRECEDING растянется больше чем на неделю.
WITH bounds AS (
    SELECT min(occurred_at)::date AS first_day, max(occurred_at)::date AS last_day
    FROM operations
),
days AS (
    SELECT d::date AS day
    FROM bounds, generate_series(first_day, last_day, interval '1 day') AS d
),
currencies AS (
    SELECT DISTINCT currency FROM accounts
),
daily AS (
    SELECT a.currency, o.occurred_at::date AS day, sum(o.amount) AS total
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    WHERE o.type = 'EXPENSE'
    GROUP BY a.currency, o.occurred_at::date
)
SELECT d.day, c.currency, coalesce(e.total, 0) AS expenses,
       round(avg(coalesce(e.total, 0)) OVER (PARTITION BY c.currency ORDER BY d.day
                                             ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS moving_avg_7d
FROM days d
CROSS JOIN currencies c
LEFT JOIN daily e ON e.day = d.day AND e.currency = c.currency
ORDER BY c.currency, d.day;
