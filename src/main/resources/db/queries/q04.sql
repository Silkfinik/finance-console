-- Баланс по дням нарастающим итогом, счёт «Карта» у user1.
WITH movements AS (
    SELECT occurred_at, CASE WHEN type = 'INCOME' THEN amount ELSE -amount END AS delta
    FROM operations
    WHERE account_id = 'c667b5d4-064a-1e5f-39f8-736c05428488'
    UNION ALL
    SELECT occurred_at, amount
    FROM operations
    WHERE target_account_id = 'c667b5d4-064a-1e5f-39f8-736c05428488'
),
daily AS (
    SELECT occurred_at::date AS day, sum(delta) AS delta
    FROM movements
    GROUP BY occurred_at::date
)
SELECT day, delta, sum(delta) OVER (ORDER BY day) AS balance
FROM daily
ORDER BY day;
