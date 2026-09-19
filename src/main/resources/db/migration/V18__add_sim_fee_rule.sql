-- 模拟交易费率配置：天天基金 f10 初始化抓取 + 后台可维护；规则版本升级为 V2（计申赎费）。
CREATE TABLE sim_fee_rule (
    rule_id        BIGSERIAL PRIMARY KEY,
    fund_code      VARCHAR(6)  NOT NULL,
    fund_name      VARCHAR(256) NOT NULL,
    fee_type       VARCHAR(16) NOT NULL CHECK (fee_type IN ('PURCHASE','REDEEM')),
    min_days       INTEGER CHECK (min_days IS NULL OR min_days >= 0),
    max_days       INTEGER CHECK (max_days IS NULL OR max_days >= 0),
    rate           NUMERIC(8,4) NOT NULL CHECK (rate >= 0),
    discount_info  VARCHAR(64),
    data_source    VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to   DATE,
    version        INTEGER NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ,
    CHECK (fee_type <> 'REDEEM' OR min_days IS NOT NULL),
    CHECK (min_days IS NULL OR max_days IS NULL OR max_days >= min_days)
);
COMMENT ON TABLE sim_fee_rule IS '模拟组合申赎费率配置；申购费率与按持有自然天数的赎回分档，来源为天天基金抓取或人工维护';
COMMENT ON COLUMN sim_fee_rule.fund_code IS '基金份额类别代码';
COMMENT ON COLUMN sim_fee_rule.fund_name IS '基金名称快照';
COMMENT ON COLUMN sim_fee_rule.fee_type IS 'PURCHASE 申购或 REDEEM 赎回';
COMMENT ON COLUMN sim_fee_rule.min_days IS '赎回分档持有自然天数下界（含），申购为 NULL';
COMMENT ON COLUMN sim_fee_rule.max_days IS '赎回分档持有自然天数上界（含），最高档为 NULL 表示无上限';
COMMENT ON COLUMN sim_fee_rule.rate IS '生效费率，0.0008 表示 0.08%';
COMMENT ON COLUMN sim_fee_rule.discount_info IS '折扣说明，如 支付宝1折';
COMMENT ON COLUMN sim_fee_rule.data_source IS 'EASTMONEY_F10 抓取或 MANUAL 人工维护';
COMMENT ON COLUMN sim_fee_rule.effective_from IS '生效开始日期';
COMMENT ON COLUMN sim_fee_rule.effective_to IS '生效结束日期，NULL 表示持续有效';
COMMENT ON COLUMN sim_fee_rule.version IS '乐观并发版本，后台修改时递增';
CREATE UNIQUE INDEX uq_sim_fee_rule ON sim_fee_rule(fund_code,fee_type,min_days,effective_from);
CREATE INDEX ix_sim_fee_rule_fund ON sim_fee_rule(fund_code);

-- 新订单默认按 V2 口径（净值公布即确认 + 申赎费）；历史行保持原版本不动。
ALTER TABLE sim_order ALTER COLUMN rule_version SET DEFAULT 'CN_NAV_SIM_V2_FEE';

INSERT INTO system_permission (permission_code, permission_name, module_code)
VALUES ('SIM_FEE_RULE_ADMIN', '维护模拟交易费率配置', 'ADMIN')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO role_permission (role_code, permission_code)
VALUES ('SYSTEM_ADMIN', 'SIM_FEE_RULE_ADMIN')
ON CONFLICT (role_code, permission_code) DO NOTHING;
