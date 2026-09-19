-- 申购规则 min_days 存 0 作唯一键占位（NULL 不参与唯一约束），保证同日重复抓取的 ON CONFLICT 幂等更新。
COMMENT ON COLUMN sim_fee_rule.min_days IS '赎回分档持有自然天数下界（含）；申购固定存 0 作唯一键占位，保证同日抓取幂等';
