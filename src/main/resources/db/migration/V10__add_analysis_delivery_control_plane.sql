CREATE TABLE analysis_delivery_checkpoint (
    consumer_name VARCHAR(64) PRIMARY KEY,
    last_scored_at TIMESTAMPTZ,
    last_forecast_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_analysis_delivery_checkpoint_pair CHECK (
        (last_scored_at IS NULL AND last_forecast_id IS NULL)
        OR (last_scored_at IS NOT NULL AND last_forecast_id IS NOT NULL)
    )
);

ALTER TABLE alert_rule
    ADD COLUMN cooldown_hours INTEGER NOT NULL DEFAULT 24,
    ADD COLUMN last_triggered_at TIMESTAMPTZ;

ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_cooldown_hours
        CHECK (cooldown_hours >= 1 AND cooldown_hours <= 720);

ALTER TABLE notification
    ADD COLUMN trigger_type VARCHAR(32),
    ADD COLUMN trigger_ref VARCHAR(128),
    ADD COLUMN signal_id UUID REFERENCES signal_snapshot (signal_id);

ALTER TABLE notification
    ADD CONSTRAINT ck_notification_trigger_type
        CHECK (trigger_type IS NULL OR trigger_type IN ('SIGNAL', 'EVENT', 'RISK'));

CREATE INDEX ix_notification_signal_created
    ON notification (signal_id, created_at DESC);
