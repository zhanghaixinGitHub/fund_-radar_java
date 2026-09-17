CREATE TABLE holding_diagnosis_report (
    report_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(user_id) ON DELETE CASCADE,
    fund_code VARCHAR(6) NOT NULL,
    fund_name VARCHAR(200) NOT NULL,
    report_date DATE NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    verdict VARCHAR(16) NOT NULL CHECK (verdict IN ('VALID','CHANGED','INSUFFICIENT')),
    items JSONB NOT NULL,
    cutoff_date DATE,
    fingerprint VARCHAR(64) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_holding_diagnosis_daily UNIQUE(user_id,fund_code,report_date,fingerprint)
);
CREATE INDEX ix_holding_diagnosis_history ON holding_diagnosis_report(user_id,fund_code,report_date DESC,generated_at DESC);

COMMENT ON TABLE holding_diagnosis_report IS '本人持仓每日诊断留档；首份报告无基线项如实记数据不足，输入变化追加版本，禁止覆盖';
COMMENT ON COLUMN holding_diagnosis_report.report_id IS '不可变诊断报告编号';
COMMENT ON COLUMN holding_diagnosis_report.user_id IS '服务端认证取得的归属用户，所有查询必须带此条件';
COMMENT ON COLUMN holding_diagnosis_report.fund_code IS '本人模拟持仓基金代码';
COMMENT ON COLUMN holding_diagnosis_report.fund_name IS '生成时的基金名称';
COMMENT ON COLUMN holding_diagnosis_report.report_date IS '实际生成时间对应的北京时间日期，不补造停机历史';
COMMENT ON COLUMN holding_diagnosis_report.generated_at IS '真实生成时刻，不是事实资料日期';
COMMENT ON COLUMN holding_diagnosis_report.verdict IS '总体结论：VALID全部成立，CHANGED任一项已改变，INSUFFICIENT无改变但有数据不足项';
COMMENT ON COLUMN holding_diagnosis_report.items IS '七项诊断逐项结论、证据、来源与数据截至日的原文快照；单项独立成立';
COMMENT ON COLUMN holding_diagnosis_report.cutoff_date IS '诊断事实的数据截至日，来源失败时为空';
COMMENT ON COLUMN holding_diagnosis_report.fingerprint IS '当日逐项判定结果去重指纹，不包含易变生成时间';
COMMENT ON COLUMN holding_diagnosis_report.content_hash IS '逐项快照SHA256（键序规范化），回读时验证完整性';

CREATE FUNCTION reject_holding_archive_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Archived holding diagnosis and drafts cannot be rewritten';
END;
$$;
CREATE TRIGGER holding_diagnosis_immutable BEFORE UPDATE ON holding_diagnosis_report
FOR EACH ROW EXECUTE FUNCTION reject_holding_archive_rewrite();
