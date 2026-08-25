ALTER TABLE alert_rule
    ADD CONSTRAINT uq_alert_rule_user_fund_type UNIQUE (user_id, fund_code, rule_type);

ALTER TABLE alert_rule
    ADD CONSTRAINT ck_alert_rule_threshold_by_type CHECK (
        (rule_type = 'RISK_LEVEL' AND threshold IS NOT NULL AND threshold >= 0 AND threshold <= 1)
        OR (rule_type IN ('SIGNAL_CHANGE', 'EVENT') AND threshold IS NULL)
    );
