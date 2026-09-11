CREATE TABLE portfolio_advice_report (
    report_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    fund_code VARCHAR(6) NOT NULL,
    fund_name VARCHAR(200) NOT NULL,
    report_date DATE NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('HOLD','SELL','UNAVAILABLE')),
    summary TEXT NOT NULL,
    rule_version VARCHAR(60) NOT NULL,
    cutoff_date DATE,
    observation_start DATE,
    observation_end DATE,
    original_report_id UUID REFERENCES portfolio_advice_report(report_id),
    sample_key VARCHAR(64),
    fingerprint VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    snapshot JSONB NOT NULL,
    CONSTRAINT uq_portfolio_advice_daily UNIQUE(user_id,fund_code,report_date,fingerprint),
    CONSTRAINT ck_portfolio_advice_window CHECK (
        (decision='UNAVAILABLE' AND observation_start IS NULL AND observation_end IS NULL AND sample_key IS NULL)
        OR (decision IN ('HOLD','SELL') AND observation_start IS NOT NULL AND observation_end>observation_start AND sample_key IS NOT NULL)
    )
);
CREATE INDEX ix_portfolio_advice_history ON portfolio_advice_report(user_id,fund_code,report_date DESC,generated_at DESC,report_id DESC);
CREATE INDEX ix_portfolio_advice_sample ON portfolio_advice_report(user_id,fund_code,sample_key) WHERE original_report_id IS NULL AND sample_key IS NOT NULL;
CREATE INDEX ix_portfolio_advice_pending ON portfolio_advice_report(observation_end,report_id) WHERE original_report_id IS NULL AND decision IN ('HOLD','SELL');

CREATE TABLE portfolio_advice_review (
    report_id UUID PRIMARY KEY REFERENCES portfolio_advice_report(report_id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL CHECK (status IN ('DATA_INSUFFICIENT','ASSESSED')),
    total_return NUMERIC(24,12),
    support VARCHAR(20) CHECK (support IN ('SUPPORTED','UNSUPPORTED','FLAT')),
    checked_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    CHECK ((status='ASSESSED' AND total_return IS NOT NULL AND support IS NOT NULL)
        OR (status='DATA_INSUFFICIENT' AND total_return IS NULL AND support IS NULL))
);

COMMENT ON TABLE portfolio_advice_report IS '本人每日操作建议原始报告；输入变化追加版本，禁止覆盖当时判断';
COMMENT ON COLUMN portfolio_advice_report.report_id IS '不可变报告编号';
COMMENT ON COLUMN portfolio_advice_report.user_id IS '服务端认证取得的归属用户，所有查询必须带此条件';
COMMENT ON COLUMN portfolio_advice_report.fund_code IS '本人模拟持仓基金代码';
COMMENT ON COLUMN portfolio_advice_report.fund_name IS '生成时的基金名称';
COMMENT ON COLUMN portfolio_advice_report.report_date IS '实际生成时间对应的北京时间日期，不补造停机历史';
COMMENT ON COLUMN portfolio_advice_report.generated_at IS '真实生成时刻，不是模型资料日期';
COMMENT ON COLUMN portfolio_advice_report.decision IS 'HOLD继续持有，SELL卖出，UNAVAILABLE尚无可用建议';
COMMENT ON COLUMN portfolio_advice_report.summary IS '卡片展示的理由摘要，不含独立概率模块';
COMMENT ON COLUMN portfolio_advice_report.rule_version IS '建议映射规则版本；不同规则分别回看';
COMMENT ON COLUMN portfolio_advice_report.cutoff_date IS '采用的模型输入信息截止日期';
COMMENT ON COLUMN portfolio_advice_report.observation_start IS '实际生成后首个可按15点截止规则操作的交易日';
COMMENT ON COLUMN portfolio_advice_report.observation_end IS '观察起点之后第20个交易日';
COMMENT ON COLUMN portfolio_advice_report.original_report_id IS '相同模型数据日首次有效报告；沿用及同日修订不重复计入回看样本';
COMMENT ON COLUMN portfolio_advice_report.sample_key IS '基金、数据截止日、主模型与规则指纹；无建议时为空';
COMMENT ON COLUMN portfolio_advice_report.fingerprint IS '当日内容去重指纹，不包含易变读取时间和观察日期';
COMMENT ON COLUMN portfolio_advice_report.content_hash IS '完整原始报告快照SHA256，回读时验证完整性';
COMMENT ON COLUMN portfolio_advice_report.snapshot IS '当时持仓、全部依据、反对依据、缺口与模型身份的原文快照';
COMMENT ON TABLE portfolio_advice_review IS '建议后续表现核验，独立保存，已完成结果不自动覆盖';
COMMENT ON COLUMN portfolio_advice_review.report_id IS '首次有效建议报告编号，沿用日报共享该核验';
COMMENT ON COLUMN portfolio_advice_review.status IS 'DATA_INSUFFICIENT待补齐资料，ASSESSED已按固定口径核验';
COMMENT ON COLUMN portfolio_advice_review.total_return IS '20交易日现金分红再投回报，小数比例，不计费用';
COMMENT ON COLUMN portfolio_advice_review.support IS '后续表现支持、不支持或持平；不是实际交易盈亏';
COMMENT ON COLUMN portfolio_advice_review.checked_at IS '本次来源核验的真实时间';
COMMENT ON COLUMN portfolio_advice_review.payload IS '核验消息、单位净值、分红与来源版本证据';

CREATE FUNCTION reject_portfolio_advice_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Archived portfolio advice cannot be rewritten';
END;
$$;
CREATE TRIGGER portfolio_advice_immutable BEFORE UPDATE ON portfolio_advice_report
FOR EACH ROW EXECUTE FUNCTION reject_portfolio_advice_rewrite();
