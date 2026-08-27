# 用户认证与权限设计

## 1. 调用链

```text
Vue（Cookie + X-CSRF-Token）
  -> Java /api/v1/auth/register 或 /api/v1/auth/login（仅有的匿名账户入口）
  -> Java AuthWebInterceptor（会话恢复、Origin + CSRF 校验）
  -> CurrentUserContext（请求线程身份）
  -> Controller requirePermission(...)
  -> Service（本人 userId 数据范围与审计）
```

FastAPI 继续只接受 Java 服务身份调用，不处理浏览器登录、角色、用户账户或个人持仓权限。

## 2. 会话与 CSRF

- 注册或登录成功下发随机不透明会话 Cookie（`HttpOnly`、`SameSite=Lax`）和配对 CSRF Cookie；数据库仅保存会话令牌 SHA-256 摘要。
- 除注册、登录和 `OPTIONS` 外，`/api/v1/**` 都必须有有效活动会话。`POST`、`PUT`、`PATCH`、`DELETE` 还必须通过 Origin 白名单和 Cookie/Header 常量时间 CSRF 比对。
- Vue `fetch` 使用 `credentials: include`；写请求从非 HttpOnly CSRF Cookie 读取令牌。Cookie 原值、密码和手机号原文都不写入 Pinia、日志或审计。

## 3. 数据迁移

| 版本 | 目的 | 兼容性原则 |
| --- | --- | --- |
| V4 | 补充账户密码哈希、旧本机账户禁用、会话表 | 已执行历史，不修改 |
| V5 | 三角色、权限字典和角色权限矩阵 | 已存在/待执行历史，不回写原语义 |
| V6 | 将 `login_name` 重命名为 `mobile`，并限制为大陆手机号或禁用历史账户标识 | 仅增量收敛，保留旧账户和业务外键 |

密码使用 BCrypt。手机号在 `user_account.mobile` 保存以供唯一登录查询；API 输出统一为掩码，审计主体使用 `user_id` 而非手机号。

## 4. API 与授权

| 接口 | 身份/权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/auth/register` | 匿名 | `{mobile,password}`；创建默认基金用户并建立会话；手机号冲突返回 `409` |
| `POST /api/v1/auth/login` | 匿名 | `{mobile,password}`；只校验已注册账户，未知手机号统一返回 `401` |
| `GET /api/v1/auth/me`、`POST /logout` | 已登录 | 恢复/撤销会话 |
| `/api/v1/funds/**` | `FUND_READ` | 登录后基金展示 |
| `/watchlist`、`/alert-rules`、`/portfolio/current` | 本人权限 | 服务内部使用当前上下文 `user_id` |
| `/sync-jobs/**`、`/health`、`/system/ai-health` | 对应后台权限 | 数据运营或系统管理员 |
| `/api/v1/admin/users/**` | 用户/持仓管理权限 | 系统管理员；脱敏账户、角色、状态、人工重置、指定用户持仓 |
| `/api/v1/admin/legacy-watchlist/transfer` | `LEGACY_WATCHLIST_TRANSFER` | `confirmed=true` 后仅迁移关注 |

`SYSTEM_ADMIN` 在 `AuthenticatedUser.hasPermission` 中对所有枚举权限恒为允许，保证新增权限不会因数据库种子遗漏而让超级管理员失权；管理操作仍记录审计。

## 5. 部署与恢复

- 仅在受保护的本地/部署环境中设置 `FUND_AUTH_INITIAL_ADMIN_MOBILE` 与 `FUND_AUTH_INITIAL_ADMIN_PASSWORD`；不要把值写入 `.env` 跟踪文件或提交。
- 生产环境应将 `FUND_AUTH_COOKIE_SECURE=true` 并使用 HTTPS。CORS 仅允许已配置前端来源且允许凭据。
- 迁移前备份 `user_account`、`auth_session`、`watchlist_item`、`alert_rule`、`portfolio_snapshot`。Flyway 迁移不可通过修改历史文件回滚；若 V6 后需恢复，应在维护窗口以数据库备份和明确的反向 SQL 执行。

## 6. v1.1 账户创建边界

`POST /register` 以 `INSERT ... ON CONFLICT (mobile) DO NOTHING` 创建账户，避免并发重复注册导致 PostgreSQL 事务进入失败状态；受影响行数为 0 时转换为 `ACCOUNT_ALREADY_EXISTS`。`POST /login` 只读取凭据并校验 BCrypt 哈希，不包含任何写账户逻辑。密码策略在注册、管理员创建、人工重置和首位管理员初始化四条路径统一校验 6 至 20 位，拒绝纯空白密码。
