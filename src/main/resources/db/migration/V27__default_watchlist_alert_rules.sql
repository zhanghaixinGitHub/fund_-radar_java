-- 关注即订阅两类站内提醒。只补缺少的设置，绝不覆盖用户已经关闭的选择或已有风险阈值。
-- 与关注同事务执行，关注失败/额度不足回滚时，默认订阅和审计也会一起回滚。
CREATE FUNCTION create_watchlist_default_alert_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    WITH created AS (
        INSERT INTO alert_rule (rule_id, user_id, fund_code, rule_type, threshold, enabled)
        SELECT gen_random_uuid(), NEW.user_id, NEW.fund_code, kind, NULL, TRUE
        FROM (VALUES ('EVENT'), ('SIGNAL_CHANGE')) AS defaults(kind)
        ON CONFLICT (user_id, fund_code, rule_type) DO NOTHING
        RETURNING rule_id, user_id
    )
    INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
    SELECT gen_random_uuid(), 'watchlist-default:' || NEW.watchlist_item_id::text,
           user_id::text, 'ALERT_RULE_DEFAULT_CREATED', rule_id::text FROM created;
    RETURN NEW;
END;
$$;

-- 覆盖正常关注和历史关注归属迁移；取消关注保留偏好，再次关注不会重新打开手动关闭项。
CREATE TRIGGER watchlist_default_alert_rules
AFTER INSERT OR UPDATE OF user_id, fund_code ON watchlist_item
FOR EACH ROW EXECUTE FUNCTION create_watchlist_default_alert_rules();

-- 已关注基金与新关注采用相同默认行为；旧通知、已读记录、规则主键和时间不做改写。
WITH created AS (
    INSERT INTO alert_rule (rule_id, user_id, fund_code, rule_type, threshold, enabled)
    SELECT gen_random_uuid(), item.user_id, item.fund_code, kind, NULL, TRUE
    FROM watchlist_item item
    CROSS JOIN (VALUES ('EVENT'), ('SIGNAL_CHANGE')) AS defaults(kind)
    ON CONFLICT (user_id, fund_code, rule_type) DO NOTHING
    RETURNING rule_id, user_id
)
INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
SELECT gen_random_uuid(), 'migration:V27', user_id::text, 'ALERT_RULE_DEFAULT_CREATED', rule_id::text FROM created;
