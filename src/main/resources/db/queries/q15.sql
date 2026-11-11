-- Поиск по подстроке описания без учёта регистра (под него GIN trgm в V3).
SELECT o.id, o.account_id, o.amount, o.description, o.occurred_at
FROM operations o
WHERE o.description ILIKE '%аптек%'
ORDER BY o.occurred_at DESC;
