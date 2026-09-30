-- 公共事件与本人条件复查共用状态记录；旧通知原文、旧订阅与已读记录不改写。
CREATE TABLE review_notice (
    notice_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id),
    business_key VARCHAR(256) NOT NULL,
    scope VARCHAR(16),
    fund_code VARCHAR(32),
    kind VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL CHECK(revision > 0),
    lifecycle VARCHAR(16) NOT NULL CHECK(lifecycle IN ('ACTIVE','RESOLVED','RETRACTED','EXPIRED')),
    material_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    valid_until DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_revision BIGINT NOT NULL DEFAULT 0,
    read_at TIMESTAMPTZ,
    UNIQUE(user_id,business_key),
    CHECK(scope IS NULL OR scope IN ('CONFIRMED','SIMULATED')),
    CHECK(read_revision >= 0 AND read_revision <= revision),
    CHECK((kind = 'FUND_PUBLICATION' AND scope IS NULL AND fund_code IS NOT NULL)
       OR (kind IN ('USE_DATE','REVIEW_DUE') AND scope IS NOT NULL AND fund_code IS NULL))
);
CREATE INDEX ix_review_notice_user_updated ON review_notice(user_id,updated_at DESC,notice_id);
CREATE TABLE review_notice_revision (
    notice_id UUID NOT NULL REFERENCES review_notice(notice_id),
    revision BIGINT NOT NULL,
    lifecycle VARCHAR(16) NOT NULL,
    material_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(notice_id,revision)
);
CREATE FUNCTION reject_review_notice_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'review notice history is immutable'; END;
$$;
CREATE TRIGGER review_notice_history_immutable BEFORE UPDATE OR DELETE ON review_notice_revision
FOR EACH ROW EXECUTE FUNCTION reject_review_notice_history_mutation();
COMMENT ON TABLE review_notice IS '本人站内复查提醒当前状态；已读版本与事实生命周期独立，不发送外部通知';
COMMENT ON COLUMN review_notice.business_key IS '本人范围内同一事项的稳定键，不包含取得时间以避免重复提醒';
COMMENT ON COLUMN review_notice.scope IS 'CONFIRMED 本人确认快照或 SIMULATED 模拟组合，公共基金消息为空';
COMMENT ON COLUMN review_notice.lifecycle IS 'ACTIVE 待复查、RESOLVED 条件已解除、RETRACTED 依据已撤销、EXPIRED 信息观察期结束';
COMMENT ON COLUMN review_notice.material_hash IS '实质内容摘要；取得时间或重复同步不改变状态';
COMMENT ON COLUMN review_notice.payload IS '保存原条件、当前事实、依据日期、复查入口；本人条件不传给 Python';
COMMENT ON COLUMN review_notice.valid_until IS '消息观察窗口截止日，不代表风险消失或投资期限';
COMMENT ON COLUMN review_notice.read_revision IS '本人已读到的版本，新实质变化使提醒再次未读，已读不解除条件';
COMMENT ON TABLE review_notice_revision IS '复查提醒不可变变化历史，按本人归属查询';
