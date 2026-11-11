-- Топ-5 категорий расходов user1 за октябрь 2026. Валюты не смешиваем.
SELECT c.name AS category, a.currency, sum(o.amount) AS total, count(*) AS operations
FROM operations o
JOIN accounts a ON a.id = o.account_id
JOIN users u ON u.id = a.user_id
JOIN categories c ON c.id = o.category_id
WHERE u.email = 'user1@example.com'
  AND o.type = 'EXPENSE'
  AND o.occurred_at >= timestamptz '2026-10-01'
  AND o.occurred_at < timestamptz '2026-11-01'
GROUP BY c.name, a.currency
ORDER BY total DESC
LIMIT 5;
