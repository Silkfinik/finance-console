-- Баланс счетов на конец каждого завершившегося месяца 2026 года.
-- Считаем по сетке счёт × месяц, чтобы месяцы без операций не пропадали.
WITH movements AS (
    SELECT account_id, occurred_at,
           CASE WHEN type = 'INCOME' THEN amount ELSE -amount END AS delta
    FROM operations
    UNION ALL
    SELECT target_account_id, occurred_at, amount
    FROM operations
    WHERE target_account_id IS NOT NULL
),
monthly AS (
    SELECT account_id, date_trunc('month', occurred_at) AS month, sum(delta) AS delta
    FROM movements
    GROUP BY account_id, date_trunc('month', occurred_at)
),
grid AS (
    SELECT a.id AS account_id, m.month
    FROM accounts a
    CROSS JOIN generate_series((SELECT min(month) FROM monthly),
                               least(timestamptz '2026-12-01', date_trunc('month', now()) - interval '1 month'),
                               interval '1 month') AS m(month)
),
running AS (
    SELECT g.account_id, g.month,
           sum(coalesce(mo.delta, 0)) OVER (PARTITION BY g.account_id ORDER BY g.month) AS balance
    FROM grid g
    LEFT JOIN monthly mo ON mo.account_id = g.account_id AND mo.month = g.month
)
SELECT u.email, a.name AS account, a.currency, r.month::date AS month,
       r.balance AS balance_at_month_end
FROM running r
JOIN accounts a ON a.id = r.account_id
JOIN users u ON u.id = a.user_id
WHERE r.month >= timestamptz '2026-01-01'
ORDER BY u.email, a.name, r.month;
