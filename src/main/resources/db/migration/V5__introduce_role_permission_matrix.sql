CREATE TABLE system_role (
    role_code VARCHAR(32) PRIMARY KEY,
    role_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_role_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

INSERT INTO system_role (role_code, role_name) VALUES
    ('FUND_USER', '基金用户'),
    ('DATA_OPERATOR', '数据运营'),
    ('SYSTEM_ADMIN', '系统管理员');

ALTER TABLE user_account DROP CONSTRAINT ck_user_account_role;

UPDATE user_account
SET role = CASE role
    WHEN 'ADMIN' THEN 'SYSTEM_ADMIN'
    ELSE 'FUND_USER'
END;

ALTER TABLE user_account
    ADD CONSTRAINT ck_user_account_role CHECK (role IN ('FUND_USER', 'DATA_OPERATOR', 'SYSTEM_ADMIN'));

ALTER TABLE user_account
    ADD CONSTRAINT fk_user_account_role FOREIGN KEY (role) REFERENCES system_role (role_code);

CREATE TABLE system_permission (
    permission_code VARCHAR(64) PRIMARY KEY,
    permission_name VARCHAR(128) NOT NULL,
    module_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO system_permission (permission_code, permission_name, module_code) VALUES
    ('FUND_READ', '查看基金展示页面', 'FRONT'),
    ('WATCHLIST_SELF_READ', '查看本人关注列表', 'FRONT'),
    ('WATCHLIST_SELF_WRITE', '维护本人关注列表', 'FRONT'),
    ('ALERT_RULE_SELF_READ', '查看本人提醒规则', 'FRONT'),
    ('ALERT_RULE_SELF_WRITE', '维护本人提醒规则', 'FRONT'),
    ('PORTFOLIO_SELF_READ', '查看本人持仓快照', 'FRONT'),
    ('ADMIN_DASHBOARD_VIEW', '查看后台工作台', 'ADMIN'),
    ('SYSTEM_HEALTH_READ', '查看系统运行状态', 'ADMIN'),
    ('SYNC_JOB_READ', '查看同步任务', 'ADMIN'),
    ('SYNC_JOB_START', '发起同步任务', 'ADMIN'),
    ('USER_ACCOUNT_READ', '查看用户账户', 'ADMIN'),
    ('USER_ACCOUNT_MANAGE', '创建和停用用户账户', 'ADMIN'),
    ('LEGACY_WATCHLIST_TRANSFER', '迁移待归属历史关注', 'ADMIN');

CREATE TABLE role_permission (
    role_code VARCHAR(32) NOT NULL REFERENCES system_role (role_code) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL REFERENCES system_permission (permission_code) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_code, permission_code)
);

INSERT INTO role_permission (role_code, permission_code)
SELECT 'FUND_USER', permission_code
FROM system_permission
WHERE permission_code IN (
    'FUND_READ', 'WATCHLIST_SELF_READ', 'WATCHLIST_SELF_WRITE',
    'ALERT_RULE_SELF_READ', 'ALERT_RULE_SELF_WRITE', 'PORTFOLIO_SELF_READ'
);

INSERT INTO role_permission (role_code, permission_code)
SELECT 'DATA_OPERATOR', permission_code
FROM system_permission
WHERE permission_code IN (
    'FUND_READ', 'WATCHLIST_SELF_READ', 'WATCHLIST_SELF_WRITE',
    'ALERT_RULE_SELF_READ', 'ALERT_RULE_SELF_WRITE', 'PORTFOLIO_SELF_READ',
    'ADMIN_DASHBOARD_VIEW', 'SYSTEM_HEALTH_READ', 'SYNC_JOB_READ', 'SYNC_JOB_START'
);

INSERT INTO role_permission (role_code, permission_code)
SELECT 'SYSTEM_ADMIN', permission_code
FROM system_permission;
