CREATE TABLE holding_rule_draft (
    draft_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    fund_code VARCHAR(6) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    stats_cutoff_date DATE NOT NULL,
    stats JSONB NOT NULL,
    tiers JSONB NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    CONSTRAINT uq_holding_rule_draft UNIQUE(user_id,fund_code,fingerprint)
);
CREATE INDEX ix_holding_rule_draft_latest ON holding_rule_draft(user_id,fund_code,generated_at DESC,draft_id DESC);

CREATE TABLE holding_rule_profile (
    rule_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    fund_code VARCHAR(6) NOT NULL,
    tier VARCHAR(16) NOT NULL CHECK (tier IN ('CONSERVATIVE','BALANCED','LOOSE','CUSTOM')),
    take_profit_pct NUMERIC(10,4),
    reduce_drawdown_pct NUMERIC(10,4),
    rule_params JSONB NOT NULL,
    source_draft_id UUID REFERENCES holding_rule_draft(draft_id),
    rule_version VARCHAR(60) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    confirmed_at TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_holding_rule_active ON holding_rule_profile(user_id,fund_code) WHERE status='ACTIVE';
CREATE INDEX ix_holding_rule_history ON holding_rule_profile(user_id,fund_code,confirmed_at DESC,rule_id DESC);

COMMENT ON TABLE holding_rule_draft IS '本人止盈/减仓线规则草案；统计指纹未变不重复生成，禁止覆盖；草案须本人确认后才生效';
COMMENT ON COLUMN holding_rule_draft.draft_id IS '不可变草案编号';
COMMENT ON COLUMN holding_rule_draft.user_id IS '服务端认证取得的归属用户，所有查询必须带此条件';
COMMENT ON COLUMN holding_rule_draft.fund_code IS '本人模拟持仓基金代码';
COMMENT ON COLUMN holding_rule_draft.generated_at IS '真实生成时刻，不是统计资料日期';
COMMENT ON COLUMN holding_rule_draft.stats_cutoff_date IS '统计所用净值截止日';
COMMENT ON COLUMN holding_rule_draft.stats IS '分位值、波动率、同类分位及样本量等统计原文，可复算可审计';
COMMENT ON COLUMN holding_rule_draft.tiers IS '保守/适中/宽松三档阈值及各自触发次数、续跌中位、修复天数统计';
COMMENT ON COLUMN holding_rule_draft.fingerprint IS '统计内容去重指纹；统计未变时沿用既有草案';
COMMENT ON TABLE holding_rule_profile IS '本人已确认持仓规则；同一基金最多一条生效，新确认取代旧规则并留痕，撤销不删除历史';
COMMENT ON COLUMN holding_rule_profile.rule_id IS '不可变规则编号';
COMMENT ON COLUMN holding_rule_profile.user_id IS '服务端认证取得的归属用户，所有查询必须带此条件';
COMMENT ON COLUMN holding_rule_profile.fund_code IS '本人模拟持仓基金代码';
COMMENT ON COLUMN holding_rule_profile.tier IS '档位：CONSERVATIVE保守，BALANCED适中，LOOSE宽松，CUSTOM本人微调';
COMMENT ON COLUMN holding_rule_profile.take_profit_pct IS '止盈线收益触发阈值，小数比例，达到后仅提示复核';
COMMENT ON COLUMN holding_rule_profile.reduce_drawdown_pct IS '减仓线回撤触发阈值，负数小数，达到后仅提示复核';
COMMENT ON COLUMN holding_rule_profile.rule_params IS '确认时参数原文：来源档位、草案阈值与微调说明';
COMMENT ON COLUMN holding_rule_profile.source_draft_id IS '确认所依据的草案编号；草案后续刷新不自动替换已确认阈值';
COMMENT ON COLUMN holding_rule_profile.rule_version IS '规则口径版本；不同版本分别回看';
COMMENT ON COLUMN holding_rule_profile.status IS 'ACTIVE生效中，REVOKED已撤销或被取代';
COMMENT ON COLUMN holding_rule_profile.confirmed_at IS '本人显式确认的真实时刻';
COMMENT ON COLUMN holding_rule_profile.superseded_at IS '被新确认规则取代的时刻；主动撤销时为空';

CREATE TRIGGER holding_rule_draft_immutable BEFORE UPDATE ON holding_rule_draft
FOR EACH ROW EXECUTE FUNCTION reject_holding_archive_rewrite();
