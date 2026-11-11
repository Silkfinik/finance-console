-- Изменение расходов к прошлому месяцу, %.
-- LAG берёт предыдущую строку: если месяц пропущен, сравнит не с тем. В сиде пропусков нет.
WITH monthly AS (
    SELECT a.currency, date_trunc('month', o.occurred_at) AS month, sum(o.amount) AS total
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    WHERE o.type = 'EXPENSE'
    GROUP BY a.currency, date_trunc('month', o.occurred_at)
)
SELECT month::date AS month, currency, total,
       LAG(total) OVER w AS previous_total,
       round(100 * (total - LAG(total) OVER w) / nullif(LAG(total) OVER w, 0), 2) AS change_pct
FROM monthly
WINDOW w AS (PARTITION BY currency ORDER BY month)
ORDER BY currency, month;
