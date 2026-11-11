-- Медиана суммы расхода по категориям.
SELECT c.name AS category, a.currency,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY o.amount) AS median_amount,
       count(*) AS operations
FROM operations o
JOIN accounts a ON a.id = o.account_id
JOIN categories c ON c.id = o.category_id
WHERE o.type = 'EXPENSE'
GROUP BY c.name, a.currency
ORDER BY c.name, a.currency;
