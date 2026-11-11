-- Операции счёта за период (q02, q04, q07, q14). INCLUDE нужен для index-only scan.
CREATE INDEX operations_account_id_occurred_at_idx
    ON operations (account_id, occurred_at DESC)
    INCLUDE (type, amount, category_id);

-- Входящие переводы (q01, q04, q11), заодно индекс под FK.
-- target_account_id заполнен только у TRANSFER, поэтому частичный.
CREATE INDEX operations_target_account_id_occurred_at_idx
    ON operations (target_account_id, occurred_at DESC)
    WHERE target_account_id IS NOT NULL;

-- ILIKE '%...%' (q15).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX operations_description_trgm_idx
    ON operations USING gin (description gin_trgm_ops);
