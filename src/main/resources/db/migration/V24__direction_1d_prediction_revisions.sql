-- 旧记录原文不变；新增顺序列的默认值 0 代表迁移前记录，不倒填其生成时间。
ALTER TABLE direction_1d_forecast ADD COLUMN revision_sequence bigint NOT NULL DEFAULT 0;
ALTER TABLE direction_1d_forecast ADD COLUMN input_identity char(64);
COMMENT ON COLUMN direction_1d_forecast.revision_sequence IS 'Python 实际输入顺序，迁移前记录为 0；不是生成完成时间';
COMMENT ON COLUMN direction_1d_forecast.input_identity IS '实际参与预测的资料和模型摘要；历史单版本记录为空';
ALTER TABLE direction_1d_forecast ADD CONSTRAINT ck_direction_revision_identity CHECK (
    (revision_sequence=0 AND input_identity IS NULL) OR
    (revision_sequence>0 AND input_identity ~ '^[a-f0-9]{64}$'));

-- 只替换原四列唯一约束，保留全部主键、外键、历史原文和不可变触发器。
DO $$ DECLARE old_name text; BEGIN
  SELECT c.conname INTO STRICT old_name FROM pg_constraint c
  WHERE c.conrelid='direction_1d_forecast'::regclass AND c.contype='u'
    AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
         FROM unnest(c.conkey) WITH ORDINALITY k(attnum,ordinality)
         JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum)
      =ARRAY['protocol','cohort_id','fund_code','target_nav_date'];
  EXECUTE format('ALTER TABLE direction_1d_forecast DROP CONSTRAINT %I',old_name);
END $$;
ALTER TABLE direction_1d_forecast ADD CONSTRAINT uq_direction_revision
    UNIQUE(protocol,cohort_id,fund_code,target_nav_date,revision_sequence);
CREATE INDEX ix_direction_forecast_hash ON direction_1d_forecast(fund_code,target_nav_date,protocol,content_hash);

CREATE TABLE direction_1d_current (
    protocol varchar(32) NOT NULL, fund_code varchar(32) NOT NULL, target_nav_date date NOT NULL,
    forecast_id uuid NOT NULL REFERENCES direction_1d_forecast(forecast_id),
    revision_sequence bigint NOT NULL, selected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(protocol,fund_code,target_nav_date));
COMMENT ON TABLE direction_1d_current IS '当前有效预测引用；旧预测和首次结果永久保留，切换需早于截止';
COMMENT ON COLUMN direction_1d_current.protocol IS '一日预测协议，不与其他周期混算';
COMMENT ON COLUMN direction_1d_current.fund_code IS '公共基金份额代码，不含用户身份';
COMMENT ON COLUMN direction_1d_current.target_nav_date IS '预测目标估值日';
COMMENT ON COLUMN direction_1d_current.forecast_id IS '当前已保存且回执核验通过的预测';
COMMENT ON COLUMN direction_1d_current.revision_sequence IS '当前采用的输入顺序，仅能前进';
COMMENT ON COLUMN direction_1d_current.selected_at IS '实际切换时刻；迁移时为建立引用的时刻，不是旧预测生成时刻';

-- 迁移仅建立已有 VERIFIED 档案的查询索引；selected_at 保留迁移时刻，不冒称提前切换。
INSERT INTO direction_1d_current(protocol,fund_code,target_nav_date,forecast_id,revision_sequence)
SELECT DISTINCT ON(f.protocol,f.fund_code,f.target_nav_date)
    f.protocol,f.fund_code,f.target_nav_date,f.forecast_id,f.revision_sequence
FROM direction_1d_forecast f JOIN direction_1d_forecast_receipt r USING(forecast_id)
WHERE r.status='VERIFIED' AND f.generated_at<f.deadline_at AND f.stored_at<f.deadline_at
    AND r.content_hash=f.content_hash AND encode(sha256(convert_to(f.payload_json,'UTF8')),'hex')=f.content_hash
    AND r.receipt_verified_at<f.deadline_at
ORDER BY f.protocol,f.fund_code,f.target_nav_date,f.stored_at,f.forecast_id;

CREATE FUNCTION direction_1d_current_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM direction_1d_forecast f JOIN direction_1d_forecast_receipt r USING(forecast_id)
    WHERE f.forecast_id=NEW.forecast_id AND f.protocol=NEW.protocol AND f.fund_code=NEW.fund_code
      AND f.target_nav_date=NEW.target_nav_date AND f.revision_sequence=NEW.revision_sequence
      AND r.status='VERIFIED' AND r.content_hash=f.content_hash
      AND f.generated_at<f.deadline_at AND f.stored_at<f.deadline_at
      AND r.receipt_verified_at<f.deadline_at AND clock_timestamp()<f.deadline_at
  ) THEN RAISE EXCEPTION 'direction current is not verified before deadline'; END IF;
  IF TG_OP='UPDATE' AND (NEW.revision_sequence<=OLD.revision_sequence OR
      (NEW.protocol,NEW.fund_code,NEW.target_nav_date) IS DISTINCT FROM
      (OLD.protocol,OLD.fund_code,OLD.target_nav_date)) THEN
    RAISE EXCEPTION 'direction current cannot move backwards';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER direction_1d_current_guard BEFORE INSERT OR UPDATE ON direction_1d_current
    FOR EACH ROW EXECUTE FUNCTION direction_1d_current_guard();
