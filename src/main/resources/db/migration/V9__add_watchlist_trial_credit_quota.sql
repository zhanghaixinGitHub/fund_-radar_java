CREATE TABLE watchlist_credit_account (
    user_id UUID PRIMARY KEY REFERENCES user_account (user_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE watchlist_credit_ledger (
    credit_ledger_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (user_id) ON DELETE CASCADE,
    entry_type VARCHAR(40) NOT NULL,
    credit_delta INTEGER NOT NULL,
    watchlist_item_id UUID,
    source_key VARCHAR(160),
    reason VARCHAR(256) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_watchlist_credit_ledger_type CHECK (
        (entry_type IN ('ADMIN_GRANT', 'MIGRATION_GRANT') AND credit_delta > 0 AND watchlist_item_id IS NULL)
        OR (entry_type IN ('WATCHLIST_CREDIT_LOCKED', 'WATCHLIST_CREDIT_RELEASED') AND credit_delta = 0 AND watchlist_item_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_watchlist_credit_ledger_user_source_key
    ON watchlist_credit_ledger (user_id, source_key)
    WHERE source_key IS NOT NULL;

CREATE INDEX ix_watchlist_credit_ledger_user_created_at
    ON watchlist_credit_ledger (user_id, created_at DESC, credit_ledger_id DESC);

CREATE TABLE watchlist_credit_hold (
    watchlist_item_id UUID PRIMARY KEY REFERENCES watchlist_item (watchlist_item_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO watchlist_credit_account (user_id)
SELECT user_id
FROM user_account
ON CONFLICT (user_id) DO NOTHING;

WITH existing_watchlists AS (
    SELECT user_id, COUNT(*) AS watchlist_count
    FROM watchlist_item
    GROUP BY user_id
)
INSERT INTO watchlist_credit_ledger (
    credit_ledger_id, user_id, entry_type, credit_delta, source_key, reason, actor_id
)
SELECT
    md5(existing_watchlists.user_id::TEXT || ':V9_MIGRATION_GRANT')::UUID,
    existing_watchlists.user_id,
    'MIGRATION_GRANT',
    existing_watchlists.watchlist_count - 5,
    'V9_INITIAL_WATCHLIST_CREDIT',
    'V9 存量关注额度迁移',
    'migration'
FROM existing_watchlists
WHERE existing_watchlists.watchlist_count > 5
ON CONFLICT (user_id, source_key) WHERE source_key IS NOT NULL DO NOTHING;

WITH ordered_watchlists AS (
    SELECT
        watchlist_item_id,
        user_id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id
            ORDER BY created_at ASC, watchlist_item_id ASC
        ) AS watchlist_position
    FROM watchlist_item
), inserted_holds AS (
    INSERT INTO watchlist_credit_hold (watchlist_item_id)
    SELECT watchlist_item_id
    FROM ordered_watchlists
    WHERE watchlist_position > 5
    ON CONFLICT (watchlist_item_id) DO NOTHING
    RETURNING watchlist_item_id
)
INSERT INTO watchlist_credit_ledger (
    credit_ledger_id, user_id, entry_type, credit_delta, watchlist_item_id, source_key, reason, actor_id
)
SELECT
    md5(ordered_watchlists.watchlist_item_id::TEXT || ':V9_MIGRATION_HOLD')::UUID,
    ordered_watchlists.user_id,
    'WATCHLIST_CREDIT_LOCKED',
    0,
    ordered_watchlists.watchlist_item_id,
    'V9_INITIAL_WATCHLIST_HOLD:' || ordered_watchlists.watchlist_item_id::TEXT,
    'V9 存量关注积分锁定',
    'migration'
FROM ordered_watchlists
JOIN inserted_holds USING (watchlist_item_id)
WHERE ordered_watchlists.watchlist_position > 5
ON CONFLICT (user_id, source_key) WHERE source_key IS NOT NULL DO NOTHING;
