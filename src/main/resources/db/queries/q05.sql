-- Ранг траты внутри категории и валюты, 1 = самая крупная.
SELECT o.id, c.name AS category, a.currency, o.amount, o.occurred_at,
       DENSE_RANK() OVER (PARTITION BY o.category_id, a.currency ORDER BY o.amount DESC) AS rank_in_category
FROM operations o
JOIN accounts a ON a.id = o.account_id
JOIN categories c ON c.id = o.category_id
WHERE o.type = 'EXPENSE'
ORDER BY c.name, a.currency, rank_in_category;
