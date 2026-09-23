-- 新增多周期引用与综合建议，旧一日、二十日和V1报告原文全部保留。
CREATE TABLE prediction_user_link (
 user_id uuid NOT NULL REFERENCES user_account(user_id), fund_code varchar(6) NOT NULL,
 prediction_id uuid NOT NULL, model_id varchar(100) NOT NULL, model_hash varchar(64) NOT NULL,
 activation_revision bigint NOT NULL, payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(user_id,prediction_id));
CREATE TABLE prediction_task_owner (
 task_id uuid PRIMARY KEY, user_id uuid REFERENCES user_account(user_id), scope varchar(16) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE user_strategy_preference (
 user_id uuid PRIMARY KEY REFERENCES user_account(user_id), preference varchar(16) NOT NULL
 CHECK(preference IN ('SHORT','BALANCED','LONG')), updated_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE portfolio_decision_report (
 report_id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES user_account(user_id),fund_code varchar(6) NOT NULL,
 generated_at timestamptz NOT NULL, strategy_version varchar(80) NOT NULL,
 generation_status varchar(16) NOT NULL CHECK(generation_status IN ('SUCCEEDED','FAILED')),
 decision varchar(16) CHECK(decision IN ('BUY','AVOID','ADD','HOLD','REDUCE','SELL')),
 input_hash char(64) NOT NULL, content_hash char(64) NOT NULL, payload jsonb NOT NULL,
 UNIQUE(user_id,fund_code,input_hash));
CREATE INDEX ix_decision_owner_time ON portfolio_decision_report(user_id,fund_code,generated_at DESC);
CREATE TABLE strategy_replay_run (
 run_id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES user_account(user_id),
 status varchar(24) NOT NULL,spec jsonb NOT NULL,result jsonb,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),finished_at timestamptz);
COMMENT ON TABLE portfolio_decision_report IS '综合建议V2不可变原文；失败decision为空，不自动交易';
COMMENT ON TABLE strategy_replay_run IS '隔离研究账本，绝不向sim_order写入交易';
CREATE FUNCTION preserve_decision_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'decision evidence is append only'; END $$;
CREATE TRIGGER immutable_decision BEFORE UPDATE OR DELETE ON portfolio_decision_report
 FOR EACH ROW EXECUTE FUNCTION preserve_decision_evidence();
CREATE TRIGGER immutable_prediction_link BEFORE UPDATE OR DELETE ON prediction_user_link
 FOR EACH ROW EXECUTE FUNCTION preserve_decision_evidence();
INSERT INTO system_permission(permission_code,permission_name,module_code) VALUES
 ('MODEL_EXPERIMENT_ADMIN','管理实验模型与采用记录','ADMIN'),
 ('RESEARCH_RUN_ADMIN','管理预测和策略研究任务','ADMIN') ON CONFLICT DO NOTHING;
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('SYSTEM_ADMIN','MODEL_EXPERIMENT_ADMIN'),('SYSTEM_ADMIN','RESEARCH_RUN_ADMIN') ON CONFLICT DO NOTHING;
