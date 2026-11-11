-- Первые 3 операции каждого счёта. id на случай одинакового времени.
SELECT a.name AS account, u.email, t.rn, t.type, t.amount, t.occurred_at
FROM (
    SELECT o.*, ROW_NUMBER() OVER (PARTITION BY o.account_id ORDER BY o.occurred_at, o.id) AS rn
    FROM operations o
) t
JOIN accounts a ON a.id = t.account_id
JOIN users u ON u.id = a.user_id
WHERE t.rn <= 3
ORDER BY u.email, a.name, t.rn;
