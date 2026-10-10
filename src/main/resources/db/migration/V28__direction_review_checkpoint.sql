-- 核对水位与不可变预测/结果分开保存；只有结果提交和学习回执都成功后才推进。
CREATE TABLE direction_1d_review_checkpoint (
    forecast_id UUID PRIMARY KEY REFERENCES direction_1d_forecast(forecast_id),
    market_revision CHAR(64) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE direction_1d_review_checkpoint IS '一日结果已成功处理的公共行情版本，重启不重复核对相同版本';
COMMENT ON COLUMN direction_1d_review_checkpoint.forecast_id IS '不可变原预测编号';
COMMENT ON COLUMN direction_1d_review_checkpoint.market_revision IS '基准日及目标日净值、目标日分红和来源资格的内容摘要';
COMMENT ON COLUMN direction_1d_review_checkpoint.checked_at IS '核对结果及回执成功后的水位提交时刻';
