SET max_parallel_workers_per_gather = 0;
SELECT setseed(0.42);

INSERT INTO users (id, email, password_hash, role)
SELECT md5('user-' || g)::uuid, 'user' || g || '@example.com', 'not-a-hash', 'USER'
FROM generate_series(1, 10) AS g;

INSERT INTO accounts (id, user_id, name, currency)
SELECT md5('account-' || a)::uuid,
    md5('user-' || (1 + (a - 1) / 3))::uuid,
    (ARRAY['Карта', 'Наличные', 'Накопления'])[1 + (a - 1) % 3],
       CASE WHEN (a - 1) % 3 = 2 THEN 'USD' ELSE 'BYN' END
FROM generate_series(1, 30) AS a;

INSERT INTO operations (id, account_id, type, amount, category_id, description, occurred_at)
SELECT md5('op-' || s.g)::uuid,
    md5('account-' || (1 + floor(random() * 30))::int)::uuid,
    CASE WHEN s.r < 0.2 THEN 'INCOME' ELSE 'EXPENSE' END,
       CASE WHEN s.r < 0.2 THEN round((500 + random() * 2500)::numeric, 2)
            ELSE round((1 + random() * 149)::numeric, 2) END,
       CASE WHEN s.r < 0.2 THEN 1 + floor(random() * 3)::int ELSE 4 + floor(random() * 6)::int END,
       (ARRAY['Супермаркет', 'Такси', 'Кафе', 'Аптека', 'Кино', 'Коммунальные услуги'])[1 + floor(random() * 6)::int],
       timestamptz '2025-01-01 00:00:00+00' + random() * interval '669 days'
FROM (SELECT g, random() AS r FROM generate_series(1, 1000000) AS g) AS s;

UPDATE accounts a SET balance = t.total
    FROM (SELECT account_id, sum(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END) AS total
      FROM operations GROUP BY account_id) AS t
WHERE a.id = t.account_id;
