CREATE TABLE portfolio_snapshot (
    snapshot_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (user_id),
    source_kind VARCHAR(32) NOT NULL,
    data_as_of_date DATE,
    data_as_of_status VARCHAR(16) NOT NULL,
    source_description VARCHAR(512) NOT NULL,
    source_content_hash VARCHAR(64) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_portfolio_snapshot_source_kind
        CHECK (source_kind IN ('USER_CONFIRMED_SCREENSHOT')),
    CONSTRAINT ck_portfolio_snapshot_as_of_status
        CHECK (data_as_of_status IN ('KNOWN', 'UNKNOWN')),
    CONSTRAINT ck_portfolio_snapshot_as_of_date
        CHECK (
            (data_as_of_status = 'KNOWN' AND data_as_of_date IS NOT NULL)
            OR (data_as_of_status = 'UNKNOWN' AND data_as_of_date IS NULL)
        ),
    CONSTRAINT uq_portfolio_snapshot_user_content_hash UNIQUE (user_id, source_content_hash)
);

CREATE INDEX ix_portfolio_snapshot_user_imported_at
    ON portfolio_snapshot (user_id, imported_at DESC);

CREATE TABLE portfolio_holding_snapshot (
    holding_snapshot_id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL REFERENCES portfolio_snapshot (snapshot_id) ON DELETE CASCADE,
    fund_code VARCHAR(6) NOT NULL,
    fund_name VARCHAR(256) NOT NULL,
    reported_amount NUMERIC(20, 2) NOT NULL,
    reported_weight_pct NUMERIC(9, 4) NOT NULL,
    reported_daily_gain_amount NUMERIC(20, 2) NOT NULL,
    reported_holding_gain_amount NUMERIC(20, 2) NOT NULL,
    reported_holding_gain_pct NUMERIC(9, 4) NOT NULL,
    reported_cumulative_gain_amount NUMERIC(20, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_portfolio_holding_snapshot_fund_code CHECK (fund_code ~ '^[0-9]{6}$'),
    CONSTRAINT ck_portfolio_holding_snapshot_amount CHECK (reported_amount >= 0),
    CONSTRAINT ck_portfolio_holding_snapshot_weight CHECK (reported_weight_pct >= 0 AND reported_weight_pct <= 100),
    CONSTRAINT uq_portfolio_holding_snapshot_fund UNIQUE (snapshot_id, fund_code)
);

CREATE INDEX ix_portfolio_holding_snapshot_snapshot_amount
    ON portfolio_holding_snapshot (snapshot_id, reported_amount DESC, fund_code ASC);
