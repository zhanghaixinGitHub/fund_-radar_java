ALTER TABLE watchlist_item
    ADD COLUMN fund_type VARCHAR(32);

CREATE INDEX ix_watchlist_item_user_type_created_at
    ON watchlist_item (user_id, fund_type, created_at DESC, fund_code ASC);
