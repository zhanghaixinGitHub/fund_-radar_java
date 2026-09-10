CREATE TABLE sim_account (
    user_id UUID PRIMARY KEY REFERENCES user_account(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sim_account IS '每用户一个人民币模拟组合；买入为投入，卖出和分红为转出';
COMMENT ON COLUMN sim_account.user_id IS '服务端认证的组合归属用户';
COMMENT ON COLUMN sim_account.created_at IS '模拟组合创建时间';

CREATE TABLE sim_order (
    order_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES sim_account(user_id),
    fund_code VARCHAR(6) NOT NULL CHECK (fund_code ~ '^[0-9]{6}$'),
    fund_name VARCHAR(256) NOT NULL,
    side VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    amount NUMERIC(20,2),
    shares NUMERIC(24,8),
    trade_date DATE NOT NULL,
    eligible_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('MANUAL','RECURRING')),
    request_key VARCHAR(96) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    rule_version VARCHAR(48) NOT NULL DEFAULT 'CN_NAV_SIM_V1_NO_FEE',
    execution JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    UNIQUE(user_id,request_key),
    CHECK (eligible_date > trade_date),
    CHECK ((side='BUY' AND amount > 0 AND shares IS NULL) OR (side='SELL' AND shares > 0 AND amount IS NULL))
);
CREATE INDEX ix_sim_order_user_fund_date ON sim_order(user_id,fund_code,trade_date,created_at);
CREATE INDEX ix_sim_order_pending ON sim_order(trade_date) WHERE status='PENDING';
COMMENT ON TABLE sim_order IS '模拟买卖订单，记录委托和当时确认结果；不执行真实交易';

CREATE TABLE sim_position (
    user_id UUID NOT NULL REFERENCES sim_account(user_id),
    fund_code VARCHAR(6) NOT NULL,
    snapshot JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    issue VARCHAR(256),
    PRIMARY KEY(user_id,fund_code)
);
COMMENT ON TABLE sim_position IS '可由确认订单及权益事件重建的模拟持仓摘要';

CREATE TABLE sim_plan (
    plan_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES sim_account(user_id),
    fund_code VARCHAR(6) NOT NULL,
    fund_name VARCHAR(256) NOT NULL,
    amount NUMERIC(20,2) NOT NULL CHECK(amount > 0),
    frequency VARCHAR(8) NOT NULL CHECK(frequency IN ('DAILY','WEEKLY','MONTHLY')),
    day_value INTEGER NOT NULL CHECK(day_value BETWEEN 1 AND 31),
    start_date DATE NOT NULL,
    end_date DATE,
    max_periods INTEGER CHECK(max_periods BETWEEN 1 AND 10000),
    scheduled_date DATE NOT NULL,
    execution_date DATE NOT NULL,
    status VARCHAR(8) NOT NULL CHECK(status IN ('ACTIVE','PAUSED','ENDED')),
    version INTEGER NOT NULL DEFAULT 1,
    request_key UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id,request_key),
    CHECK(end_date IS NULL OR end_date >= start_date)
);
CREATE UNIQUE INDEX uq_sim_plan_active_fund ON sim_plan(user_id,fund_code) WHERE status IN ('ACTIVE','PAUSED');
CREATE INDEX ix_sim_plan_due ON sim_plan(execution_date) WHERE status='ACTIVE';
COMMENT ON TABLE sim_plan IS '模拟定投计划；修改只作用于尚未生成的期次';

CREATE TABLE sim_plan_execution (
    execution_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES sim_plan(plan_id),
    scheduled_date DATE NOT NULL,
    execution_date DATE NOT NULL,
    plan_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('ORDERED','MISSED','SKIPPED')),
    order_id UUID REFERENCES sim_order(order_id),
    message VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(plan_id,scheduled_date)
);
COMMENT ON TABLE sim_plan_execution IS '定投每期执行结果；重复触发不重复买入，停机错过不补造历史交易';

CREATE TABLE sim_ledger_entry (
    entry_id UUID PRIMARY KEY,
    event_sequence BIGSERIAL NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES sim_account(user_id),
    fund_code VARCHAR(6) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    revision VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id,event_key,revision)
);
CREATE INDEX ix_sim_ledger_user_time ON sim_ledger_entry(user_id,created_at DESC);
COMMENT ON TABLE sim_ledger_entry IS '追加式模拟账务事件及更正证据；旧版本不删除';

CREATE TABLE sim_daily_valuation (
    user_id UUID NOT NULL REFERENCES sim_account(user_id),
    fund_code VARCHAR(6) NOT NULL,
    valuation_date DATE NOT NULL,
    market_value NUMERIC(24,8) NOT NULL,
    cumulative_gain NUMERIC(24,8) NOT NULL,
    daily_gain NUMERIC(24,8),
    PRIMARY KEY(user_id,fund_code,valuation_date)
);
COMMENT ON TABLE sim_daily_valuation IS '基于实际净值日期的模拟历史收益；本金流入不算收益';

CREATE TABLE sim_job_state (
    job_name VARCHAR(64) PRIMARY KEY,
    attempted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL,
    message VARCHAR(256)
);
COMMENT ON TABLE sim_job_state IS '模拟后台任务运行水位，不保存个人财务数据';

-- 新表逐列补中文注释，缺失映射立即失败；不改动旧迁移。
DO $$
DECLARE c RECORD; label TEXT;
DECLARE labels JSONB := '{
 "user_id":"认证用户标识及数据隔离键","fund_code":"基金份额类别代码","fund_name":"基金名称快照",
 "order_id":"模拟订单标识","side":"买入或卖出","amount":"模拟投入人民币金额","shares":"委托卖出份额",
 "trade_date":"成交净值归属日期","eligible_date":"模拟最早确认日期","status":"业务状态",
 "source_kind":"手动或定投来源","request_key":"请求幂等标识","request_hash":"委托内容摘要",
 "rule_version":"模拟交易与费用口径版本","execution":"当前结算结果及净值来源版本",
 "created_at":"记录创建时间","confirmed_at":"实际处理确认时间","snapshot":"派生持仓摘要及估值日期",
 "updated_at":"最近估值更新时间","issue":"结算或行情待核对原因",
 "plan_id":"定投计划标识","frequency":"每日每周或每月","day_value":"星期或每月日期参数",
 "start_date":"计划开始日期","end_date":"可选计划结束日期","max_periods":"可选最大期数",
 "scheduled_date":"原始计划日期及期次幂等键","execution_date":"休市顺延后的执行日期","version":"乐观并发版本",
 "execution_id":"期次执行记录标识","plan_version":"执行时计划版本","message":"脱敏状态说明",
 "entry_id":"追加流水标识","event_sequence":"流水追加顺序，不受同秒执行或时钟回拨影响","event_key":"业务事件稳定标识","entry_type":"交易权益或更正事件类型",
 "payload":"当次事件完整证据","revision":"当次事件内容指纹","valuation_date":"估值所用净值日期",
 "market_value":"当日份额市值","cumulative_gain":"剔除资金流的累计收益","daily_gain":"相邻开市日收益，缺日时为空",
 "job_name":"后台任务名称","attempted_at":"最近执行开始时间","completed_at":"最近执行结束时间"
 }';
BEGIN
 FOR c IN SELECT table_name,column_name FROM information_schema.columns
   WHERE table_schema=current_schema() AND table_name IN
   ('sim_order','sim_position','sim_plan','sim_plan_execution','sim_ledger_entry','sim_daily_valuation','sim_job_state')
 LOOP
   label := labels ->> c.column_name;
   IF label IS NULL THEN RAISE EXCEPTION '模拟表字段缺少注释: %.%',c.table_name,c.column_name; END IF;
   EXECUTE format('COMMENT ON COLUMN %I.%I IS %L',c.table_name,c.column_name,label);
 END LOOP;
END $$;

INSERT INTO system_permission(permission_code,permission_name,module_code) VALUES
 ('SIM_PORTFOLIO_SELF_WRITE','维护本人模拟买卖订单','FRONT'),
 ('SIM_PLAN_SELF_WRITE','维护本人模拟定投计划','FRONT');
INSERT INTO role_permission(role_code,permission_code)
 SELECT r.role_code,p.permission_code FROM system_role r CROSS JOIN system_permission p
 WHERE p.permission_code IN ('SIM_PORTFOLIO_SELF_WRITE','SIM_PLAN_SELF_WRITE');
