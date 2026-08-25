CREATE TABLE user_account (
    user_id UUID PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

INSERT INTO user_account (user_id, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'local-user', 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

CREATE TABLE watchlist_item (
    watchlist_item_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (user_id),
    fund_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_watchlist_item_user_fund UNIQUE (user_id, fund_code)
);

CREATE INDEX ix_watchlist_item_user_created_at
    ON watchlist_item (user_id, created_at DESC);

CREATE TABLE signal_snapshot (
    signal_id UUID PRIMARY KEY,
    fund_code VARCHAR(32) NOT NULL,
    as_of_date DATE NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    feature_version VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_signal_snapshot_business UNIQUE (fund_code, as_of_date, model_version)
);

CREATE INDEX ix_signal_snapshot_fund_as_of_date
    ON signal_snapshot (fund_code, as_of_date DESC);

CREATE TABLE alert_rule (
    rule_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (user_id),
    fund_code VARCHAR(32) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    threshold NUMERIC(20, 8),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_alert_rule_type CHECK (rule_type IN ('RISK_LEVEL', 'SIGNAL_CHANGE', 'EVENT'))
);

CREATE INDEX ix_alert_rule_user_fund_enabled
    ON alert_rule (user_id, fund_code, enabled);

CREATE TABLE notification (
    notification_id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES alert_rule (rule_id),
    deduplication_key VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_notification_deduplication_key UNIQUE (deduplication_key),
    CONSTRAINT ck_notification_status CHECK (status IN ('UNREAD', 'READ'))
);

CREATE INDEX ix_notification_rule_created_at
    ON notification (rule_id, created_at DESC);

CREATE TABLE audit_log (
    audit_log_id UUID PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(128) NOT NULL,
    target_id VARCHAR(256),
    detail JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_audit_log_trace_id_occurred_at
    ON audit_log (trace_id, occurred_at DESC);
