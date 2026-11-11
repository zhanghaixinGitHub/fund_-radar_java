-- 原答案全部保留。综合分析旧实现固定读取002112，因此其他基金缺少身份的旧答案不能继续计成绩。
CREATE VIEW direction_1d_verified_outcome AS
SELECT o.*, (row_number() OVER (PARTITION BY o.forecast_id ORDER BY o.revision_no))::integer AS verified_revision_no
FROM direction_1d_outcome o JOIN direction_1d_forecast f USING(forecast_id)
WHERE f.protocol <> 'DIRECTION_1D_ANALYSIS_V1'
   OR (o.payload->>'fund_code'=f.fund_code
       AND o.payload->'base_source'->>'fund_code'=f.fund_code
       AND o.payload->'target_source'->>'fund_code'=f.fund_code)
   OR (f.fund_code='002112' AND NOT (o.payload ? 'fund_code'));
COMMENT ON VIEW direction_1d_verified_outcome IS '可用于展示和统计的一日答案；不改写或删除来源不符的旧答案';

CREATE TABLE direction_1d_outcome_check (
    forecast_id UUID PRIMARY KEY REFERENCES direction_1d_forecast(forecast_id),
    status VARCHAR(32) NOT NULL CHECK(status IN ('SUCCEEDED','PENDING_DATA','FAILED')),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE direction_1d_outcome_check IS '一日到期核对的最近实际状态，与不可变预测和答案分开保存';
COMMENT ON COLUMN direction_1d_outcome_check.forecast_id IS '已保存的原预测编号';
COMMENT ON COLUMN direction_1d_outcome_check.status IS '已核对、缺数据或核对失败；没有记录表示尚未核对';
COMMENT ON COLUMN direction_1d_outcome_check.checked_at IS '最近一次实际核对时刻';
