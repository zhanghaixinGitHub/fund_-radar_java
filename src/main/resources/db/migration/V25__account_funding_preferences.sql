-- 本人资金安排只追加版本；不改模拟交易、旧截图或确认规则。
CREATE TABLE account_funding_preference (
    preference_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id),
    scope VARCHAR(16) NOT NULL CHECK(scope IN ('CONFIRMED','SIMULATED')),
    revision BIGINT NOT NULL CHECK(revision > 0),
    status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
    payload JSONB NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(user_id, scope, revision),
    UNIQUE(user_id, request_id)
);
CREATE INDEX ix_account_funding_current ON account_funding_preference(user_id,scope,revision DESC);
CREATE FUNCTION prevent_account_funding_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'account funding history is immutable'; END;
$$;
CREATE TRIGGER account_funding_immutable BEFORE UPDATE OR DELETE ON account_funding_preference
FOR EACH ROW EXECUTE FUNCTION prevent_account_funding_mutation();
COMMENT ON TABLE account_funding_preference IS '本人显式确认的资金安排历史，真实已录入持仓与模拟组合独立';
COMMENT ON COLUMN account_funding_preference.preference_id IS '本次不可变确认记录标识';
COMMENT ON COLUMN account_funding_preference.user_id IS '从已认证会话取得的本人标识，不接受请求指定';
COMMENT ON COLUMN account_funding_preference.scope IS 'CONFIRMED为本人确认快照范围，SIMULATED为模拟组合，二者不混用';
COMMENT ON COLUMN account_funding_preference.revision IS '同一人同一范围单调递增版本，从1开始';
COMMENT ON COLUMN account_funding_preference.status IS 'ACTIVE为本人已确认，REVOKED为主动撤销记录；以最新版本为准';
COMMENT ON COLUMN account_funding_preference.request_id IS '本人确认请求的幂等标识，同一编号不得承载不同内容';
COMMENT ON COLUMN account_funding_preference.request_hash IS '确认内容摘要，只用于幂等，不写个人金额日志';
COMMENT ON COLUMN account_funding_preference.payload IS '用途、用款日期、人民币金额、愿意接受损失百分比、现实可承担损失金额和复查间隔；缺失保留null';
COMMENT ON COLUMN account_funding_preference.confirmed_at IS '数据库记录本人确认或撤销的实际时刻，不允许回填';
