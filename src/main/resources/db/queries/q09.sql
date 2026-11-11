-- Доля категории в расходах месяца, %.
WITH by_category AS (
    SELECT date_trunc('month', o.occurred_at) AS month, a.currency, c.name AS category,
           sum(o.amount) AS total
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    JOIN categories c ON c.id = o.category_id
    WHERE o.type = 'EXPENSE'
    GROUP BY date_trunc('month', o.occurred_at), a.currency, c.name
)
SELECT month::date AS month, currency, category, total,
       round(100 * total / sum(total) OVER (PARTITION BY month, currency), 2) AS share_pct
FROM by_category
ORDER BY month, currency, share_pct DESC;
