ALTER TABLE portfolio_snapshot ADD CONSTRAINT uq_portfolio_snapshot_owner_id UNIQUE(user_id,snapshot_id);
ALTER TABLE sim_account ADD COLUMN baseline_snapshot_id UUID;
ALTER TABLE sim_account ADD CONSTRAINT fk_sim_account_baseline_owner
    FOREIGN KEY(user_id,baseline_snapshot_id) REFERENCES portfolio_snapshot(user_id,snapshot_id);
COMMENT ON COLUMN sim_account.baseline_snapshot_id IS '本人明确导入的持仓截图基数；为空时仅展示模拟账本，不从历史快照自动采用';

ALTER TABLE sim_plan ADD COLUMN reported_ordered_periods BIGINT NOT NULL DEFAULT 0 CHECK(reported_ordered_periods>=0);
ALTER TABLE sim_plan ADD COLUMN reported_invested_amount NUMERIC(20,2) NOT NULL DEFAULT 0 CHECK(reported_invested_amount>=0);
COMMENT ON COLUMN sim_plan.reported_ordered_periods IS '用户截图确认的历史已投期数，独立保留原值，不推算逐期交易';
COMMENT ON COLUMN sim_plan.reported_invested_amount IS '用户截图确认的历史累计定投金额，单位元；与期数独立保存，新执行另行累计';
