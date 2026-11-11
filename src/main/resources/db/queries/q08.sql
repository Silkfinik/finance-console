-- Средние дневные расходы по дням недели (isodow: 1 = пн, 7 = вс).
WITH daily AS (
    SELECT a.currency, o.occurred_at::date AS day, sum(o.amount) AS total
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    WHERE o.type = 'EXPENSE'
    GROUP BY a.currency, o.occurred_at::date
)
SELECT extract(isodow FROM day)::int AS iso_dow, currency,
       round(avg(total), 2) AS avg_daily_expenses, count(*) AS days
FROM daily
GROUP BY extract(isodow FROM day), currency
ORDER BY currency, iso_dow;
