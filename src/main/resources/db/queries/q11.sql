-- Счета, у которых balance не сходится с суммой операций.
-- На свежем сиде пусто: seed.sql считает balance по операциям.
WITH movements AS (
    SELECT account_id, CASE WHEN type = 'INCOME' THEN amount ELSE -amount END AS delta
    FROM operations
    UNION ALL
    SELECT target_account_id, amount
    FROM operations
    WHERE target_account_id IS NOT NULL
)
SELECT a.id, a.name, a.balance, coalesce(sum(m.delta), 0) AS recalculated
FROM accounts a
LEFT JOIN movements m ON m.account_id = a.id
GROUP BY a.id, a.name, a.balance
HAVING a.balance <> coalesce(sum(m.delta), 0)
ORDER BY a.name, a.id;
