-- 收益更新与交易结算分开：仅添加观测状态，不改写历史订单、持仓起点或流水。
ALTER TABLE sim_position ADD COLUMN valuation_state JSONB;
COMMENT ON COLUMN sim_position.valuation_state IS '独立收益更新观测：实际净值日期及缺失原因，不执行交易或定投';
