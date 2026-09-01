INSERT INTO system_permission (permission_code, permission_name, module_code)
VALUES
    ('NOTIFICATION_SELF_READ', '查看本人提醒通知', 'FRONT'),
    ('NOTIFICATION_SELF_WRITE', '标记本人提醒通知已读', 'FRONT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO role_permission (role_code, permission_code)
SELECT role_code, permission_code
FROM (
    VALUES
        ('FUND_USER', 'NOTIFICATION_SELF_READ'),
        ('FUND_USER', 'NOTIFICATION_SELF_WRITE'),
        ('DATA_OPERATOR', 'NOTIFICATION_SELF_READ'),
        ('DATA_OPERATOR', 'NOTIFICATION_SELF_WRITE'),
        ('SYSTEM_ADMIN', 'NOTIFICATION_SELF_READ'),
        ('SYSTEM_ADMIN', 'NOTIFICATION_SELF_WRITE')
) AS expected_permissions(role_code, permission_code)
ON CONFLICT (role_code, permission_code) DO NOTHING;

CREATE INDEX ix_alert_rule_fund_enabled_cooldown
    ON alert_rule (fund_code, enabled, last_triggered_at);

CREATE INDEX ix_notification_rule_status_created
    ON notification (rule_id, status, created_at DESC);
