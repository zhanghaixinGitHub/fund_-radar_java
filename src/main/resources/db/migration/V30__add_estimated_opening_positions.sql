-- 期初余额是用户确认的持仓，不伪造历史申购；原始确认记录继续保留以供核对。
ALTER TABLE sim_position ADD COLUMN opening_balance JSONB;
COMMENT ON COLUMN sim_position.opening_balance IS '用户同意按金额和已公布净值折算的期初余额；含来源快照、启用日期、参考净值、估算份额、成本及累计收益；结算重放时不可覆盖';
