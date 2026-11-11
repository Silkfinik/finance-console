-- Выписка по счёту за октябрь 2026, новые сверху, первая страница.
-- Следующую страницу брать через occurred_at < последнего показанного.
SELECT o.id, o.type, o.amount, c.name AS category, o.description, o.occurred_at
FROM operations o
LEFT JOIN categories c ON c.id = o.category_id
WHERE o.account_id = 'c667b5d4-064a-1e5f-39f8-736c05428488'
  AND o.occurred_at >= timestamptz '2026-10-01'
  AND o.occurred_at < timestamptz '2026-11-01'
ORDER BY o.occurred_at DESC
LIMIT 50;
