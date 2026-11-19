-- 独立保存检查水位。条件解除仅更新水位，不制造新消息或改写已读历史。
CREATE TABLE reminder_delivery_state (
    user_id UUID NOT NULL REFERENCES user_account(user_id),
    rule_key TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    setting TEXT NOT NULL,
    observed_value TEXT NOT NULL,
    PRIMARY KEY (user_id, rule_key)
);

DO $$
DECLARE old_constraint TEXT;
BEGIN
    SELECT conname INTO STRICT old_constraint FROM pg_constraint
        WHERE conrelid = 'review_notice'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%FUND_PUBLICATION%';
    EXECUTE format('ALTER TABLE review_notice DROP CONSTRAINT %I', old_constraint);
END $$;

ALTER TABLE review_notice ADD CONSTRAINT ck_review_notice_kind_scope CHECK (
    (kind IN ('FUND_PUBLICATION','SIGNAL_CHANGE','RISK_LEVEL') AND scope IS NULL AND fund_code IS NOT NULL)
    OR (kind IN ('TAKE_PROFIT','HOLDING_LOSS','DRAWDOWN') AND scope = 'SIMULATED' AND fund_code IS NOT NULL)
    OR (kind IN ('USE_DATE','REVIEW_DUE') AND scope IS NOT NULL AND fund_code IS NULL)
);
