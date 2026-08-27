INSERT INTO system_permission (permission_code, permission_name, module_code)
VALUES ('PORTFOLIO_USER_READ', '查看指定用户持仓快照', 'ADMIN')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO role_permission (role_code, permission_code)
VALUES ('SYSTEM_ADMIN', 'PORTFOLIO_USER_READ')
ON CONFLICT (role_code, permission_code) DO NOTHING;
