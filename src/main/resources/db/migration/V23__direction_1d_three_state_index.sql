-- 新旧一日协议并存，只补查询索引和说明，不修改历史预测、分数或核对结果。
CREATE INDEX ix_direction_1d_forecast_protocol_fund
  ON direction_1d_forecast(protocol,fund_code,target_nav_date,stored_at);
COMMENT ON COLUMN direction_1d_forecast.protocol IS 'V1 为上涨/非上涨；V2 为单位净值上涨/持平/下跌，成绩分别统计';
COMMENT ON COLUMN direction_1d_forecast_score.predicted_direction IS 'V1: UP/NON_UP；V2: UP/FLAT/DOWN，旧记录保持原分类';
COMMENT ON COLUMN direction_1d_forecast_score.score IS '未校准内部分类分数；V1 为上涨类分数，V2 为胜出类别分数；不是正式概率';
