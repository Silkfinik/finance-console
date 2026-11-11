-- Пользователи без операций за последние 30 дней.
SELECT u.id, u.email
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM operations o
    JOIN accounts a ON a.id = o.account_id
    WHERE a.user_id = u.id
      AND o.occurred_at >= now() - interval '30 days'
)
ORDER BY u.email;
