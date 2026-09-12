-- 一交易日实验独立表；不更改20日研究、正式发布或持仓建议。
CREATE FUNCTION direction_1d_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'direction_1d immutable evidence'; END $$;

CREATE TABLE direction_1d_subscription (
 user_id uuid PRIMARY KEY REFERENCES user_account(user_id), enabled boolean NOT NULL DEFAULT false,
 enabled_at timestamptz, disabled_at timestamptz, updated_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE direction_1d_scope_snapshot (
 snapshot_id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES user_account(user_id),
 captured_at timestamptz NOT NULL DEFAULT clock_timestamp(), target_nav_date date NOT NULL,
 items jsonb NOT NULL, scope_hash char(64) NOT NULL);
CREATE INDEX ix_direction_1d_scope_user ON direction_1d_scope_snapshot(user_id,captured_at DESC);
CREATE TABLE direction_1d_forecast (
 forecast_id uuid PRIMARY KEY, source_job_id uuid NOT NULL, protocol varchar(32) NOT NULL,
 cohort_id varchar(80) NOT NULL, fund_code varchar(32) NOT NULL,
 base_nav_date date NOT NULL, target_nav_date date NOT NULL, calendar_version char(64) NOT NULL,
 window_open_at timestamptz NOT NULL, deadline_at timestamptz NOT NULL,
 payload_json text NOT NULL, payload jsonb NOT NULL, input_hash char(64) NOT NULL, content_hash char(64) NOT NULL,
 generated_at timestamptz NOT NULL, stored_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(protocol,cohort_id,fund_code,target_nav_date));
CREATE INDEX ix_direction_1d_forecast_fund ON direction_1d_forecast(fund_code,target_nav_date DESC,forecast_id);
CREATE INDEX ix_direction_1d_forecast_pending ON direction_1d_forecast(target_nav_date,forecast_id);
CREATE TABLE direction_1d_forecast_score (
 forecast_id uuid NOT NULL REFERENCES direction_1d_forecast, branch_id varchar(32) NOT NULL,
 model_id uuid, model_hash char(64), score double precision, predicted_direction varchar(16),
 status varchar(32) NOT NULL, train_as_of timestamptz,
 PRIMARY KEY(forecast_id,branch_id), CHECK(score IS NULL OR score BETWEEN 0 AND 1));
CREATE TABLE direction_1d_forecast_receipt (
 forecast_id uuid PRIMARY KEY REFERENCES direction_1d_forecast,
 receipt_verified_at timestamptz NOT NULL, content_hash char(64) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('VERIFIED','LATE_ARCHIVE','HASH_MISMATCH')));
CREATE TABLE direction_1d_user_forecast (
 user_id uuid NOT NULL REFERENCES user_account, forecast_id uuid NOT NULL REFERENCES direction_1d_forecast,
 scope_snapshot_id uuid NOT NULL REFERENCES direction_1d_scope_snapshot,
 linked_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(user_id,forecast_id));
CREATE INDEX ix_direction_1d_user_history ON direction_1d_user_forecast(user_id,linked_at DESC,forecast_id);
CREATE TABLE direction_1d_attempt (
 attempt_id uuid PRIMARY KEY, scope_snapshot_id uuid NOT NULL REFERENCES direction_1d_scope_snapshot,
 fund_code varchar(32) NOT NULL, target_nav_date date NOT NULL, source_job_id uuid,
 attempted_at timestamptz NOT NULL DEFAULT clock_timestamp(), status varchar(32) NOT NULL,
 reasons jsonb NOT NULL, next_retry_at timestamptz, trace_id varchar(128));
CREATE INDEX ix_direction_1d_attempt_retry ON direction_1d_attempt(next_retry_at,attempt_id);
CREATE TABLE direction_1d_outcome (
 forecast_id uuid NOT NULL REFERENCES direction_1d_forecast, revision_no integer NOT NULL,
 label_snapshot_id uuid NOT NULL, label_hash char(64) NOT NULL, payload jsonb NOT NULL,
 base_unit_nav numeric(20,8) NOT NULL, target_unit_nav numeric(20,8) NOT NULL,
 y integer NOT NULL CHECK(y IN (0,1)), actual_direction varchar(8) NOT NULL,
 nav_return numeric(24,12) NOT NULL, label_observed_at timestamptz NOT NULL,
 assessed_at timestamptz NOT NULL DEFAULT clock_timestamp(), revision_reason varchar(128) NOT NULL,
 PRIMARY KEY(forecast_id,revision_no), UNIQUE(forecast_id,label_hash));
CREATE TABLE direction_1d_task_health (
 task_name varchar(32) PRIMARY KEY, state varchar(32) NOT NULL, started_at timestamptz,
 finished_at timestamptz, checked_count integer NOT NULL DEFAULT 0, failed_count integer NOT NULL DEFAULT 0,
 message varchar(512), next_run_at timestamptz);

-- 逐物理字段补中文注释，业务JSON字段含义以DIRECTION_1D_V1冻结契约为准。
DO $$ DECLARE t text; r record; descriptions jsonb := '{
"user_id":"所属认证用户编号，仅Java持有","enabled":"本人是否启用每日实验，默认关闭",
"enabled_at":"实际启用时刻","disabled_at":"实际停用时刻","updated_at":"数据库实际更新时间",
"snapshot_id":"本人范围快照编号","captured_at":"本人范围实际冻结时刻","target_nav_date":"相邻目标交易日U",
"items":"当时本人关注的代码及类型快照","scope_hash":"冻结范围原文摘要","forecast_id":"公开预测编号",
"source_job_id":"Python公共作业编号，不含用户身份","protocol":"独立1日协议版本","cohort_id":"冻结训练集合版本",
"fund_code":"基金份额代码","base_nav_date":"基准交易日T","calendar_version":"冻结沪深日历摘要",
"window_open_at":"T日北京时间18点窗口开始","deadline_at":"U日北京时间08点30分硬截止",
"payload_json":"用于逐字节验哈希的不可变公共预测原文","payload":"公开预测解析视图，含原始输入与分支",
"input_hash":"完整输入快照SHA256","content_hash":"公开预测原文字节SHA256",
"generated_at":"Python真正推理完成时刻","stored_at":"数据库真实写入时刻，不等于提交证明",
"branch_id":"FIXED或WEEKLY或简单基线","model_id":"注册模型编号","model_hash":"注册模型完整文件摘要",
"score":"未校准模型分数，不是正式上涨概率","predicted_direction":"上涨UP或非上涨NON_UP",
"status":"当前维度技术状态，不表示已正式发布","train_as_of":"模型训练成熟样本读取界限",
"receipt_verified_at":"提交后新事务回读确认时刻","scope_snapshot_id":"当时本人关注范围快照",
"linked_at":"本人关联公开预测的实际时刻，禁止截止后补关联","attempt_id":"本次尝试唯一编号",
"attempted_at":"本次真实尝试时刻","reasons":"覆盖缺口或失败原因代码数组","next_retry_at":"允许下次重试时刻",
"trace_id":"脱敏请求追踪号","revision_no":"追加核对版本，1为首次口径","label_snapshot_id":"公开答案快照编号",
"label_hash":"公开答案版本SHA256","base_unit_nav":"冻结T官方单位净值，元每份",
"target_unit_nav":"U官方单位净值，元每份","y":"U大于T为1，否则0","actual_direction":"实际UP或DOWN或FLAT",
"nav_return":"NAV(U)除以NAV(T)减1，12位小数","label_observed_at":"U答案真实接纳时刻",
"assessed_at":"Java核对完成时刻","revision_reason":"首次或来源修订的追加原因",
"task_name":"独立后台任务名称","state":"最近一次任务运行状态","started_at":"最近实际开始时刻",
"finished_at":"最近实际结束时刻","checked_count":"最近检查数量","failed_count":"最近失败数量",
"message":"脱敏状态说明","next_run_at":"预计下次后台检查时刻"}'::jsonb;
BEGIN
 FOREACH t IN ARRAY ARRAY['subscription','scope_snapshot','forecast','forecast_score','forecast_receipt',
   'user_forecast','attempt','outcome','task_health'] LOOP
   EXECUTE format('COMMENT ON TABLE direction_1d_%I IS %L',t,'独立1日实验：'||t||'；不得用于正式发布或覆盖旧结论');
   FOR r IN SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='direction_1d_'||t LOOP
     IF descriptions->>r.column_name IS NULL THEN RAISE EXCEPTION 'missing column comment %',r.column_name; END IF;
     EXECUTE format('COMMENT ON COLUMN direction_1d_%I.%I IS %L',t,r.column_name,descriptions->>r.column_name);
   END LOOP;
   IF t NOT IN ('subscription','task_health') THEN
     EXECUTE format('CREATE TRIGGER guard_direction_1d_%I BEFORE UPDATE ON direction_1d_%I FOR EACH ROW EXECUTE FUNCTION direction_1d_immutable()',t,t);
   END IF;
 END LOOP;
END $$;
