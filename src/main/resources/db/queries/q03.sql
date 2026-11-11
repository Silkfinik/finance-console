-- Расходы по месяцам за последние 12 месяцев, пустые месяцы с нулём.
WITH months AS (
    SELECT generate_series(date_trunc('month', now()) - interval '11 months',
                           date_trunc('month', now()),
                           interval '1 month') AS month
),
currencies AS (
    SELECT DISTINCT currency FROM accounts
),
monthly AS (
    SELECT a.currency, date_trunc('month', o.occurred_at) AS month, sum(o.amount) AS total
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    WHERE o.type = 'EXPENSE'
      AND o.occurred_at >= date_trunc('month', now()) - interval '11 months'
    GROUP BY a.currency, date_trunc('month', o.occurred_at)
)
SELECT m.month::date AS month, c.currency, coalesce(e.total, 0) AS expenses
FROM months m
CROSS JOIN currencies c
LEFT JOIN monthly e ON e.month = m.month AND e.currency = c.currency
ORDER BY c.currency, m.month;
