-- 实际建议版本序列的隔离模拟账本。原建议、预测和个人交易账本均不改写。
CREATE TABLE advice_effect_evidence (
 evidence_id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES user_account(user_id),fund_code varchar(6) NOT NULL,
 content_hash char(64) NOT NULL,spec jsonb NOT NULL,payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(user_id,fund_code,content_hash));
CREATE INDEX ix_advice_effect_owner ON advice_effect_evidence(user_id,fund_code,created_at DESC);
CREATE TRIGGER immutable_advice_effect BEFORE UPDATE OR DELETE ON advice_effect_evidence
 FOR EACH ROW EXECUTE FUNCTION preserve_decision_evidence();
COMMENT ON TABLE advice_effect_evidence IS '按当时已发出建议及真实生效日衔接的隔离模拟账本；净值修订追加新hash，不冒充真实账户收益';
