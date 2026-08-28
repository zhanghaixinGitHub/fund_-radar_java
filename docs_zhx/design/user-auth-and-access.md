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

## 7. v1.2 试用关注积分

### 7.1 数据模型与一致性

Flyway V9 新增以下对象，不修改历史迁移：

- `watchlist_credit_account(user_id)`：不保存余额，只作为按用户 `SELECT ... FOR UPDATE` 的串行化锁锚点。
- `watchlist_credit_ledger`：不可修改的积分流水；`ADMIN_GRANT` 与 `MIGRATION_GRANT` 为正数 `credit_delta`，`WATCHLIST_CREDIT_LOCKED` 与 `WATCHLIST_CREDIT_RELEASED` 为零额状态事件。积分总额由正负流水求和，不能由浏览器或管理员直接覆盖。
- `watchlist_credit_hold(watchlist_item_id)`：当前锁定关系。锁定数以其行数计算；持有关系只引用关注项，因此历史关注迁移变更 `user_id` 时不会留下错误的账户外键。

用户当前额度统一为：`free_limit=5`，`trial_credit_total=SUM(credit_delta)`，`trial_credit_locked=COUNT(hold)`，`trial_credit_available=total-locked`，`max_active_count=5+total`。新增或删除关注前/后先锁定 `watchlist_credit_account`；服务根据 `max(0, active_watchlist_count - 5)` 对锁定关系做确定性补齐或释放。这样并发的新增、取消和管理员发放不会超额、重复释放或出现负可用积分。

V9 迁移先为所有既有账户创建锁锚点；对已有超过 5 条关注的用户，用稳定迁移键写入一次性 `MIGRATION_GRANT`，并按 `created_at, watchlist_item_id` 的稳定顺序建立所需锁定关系。迁移重跑受唯一迁移键保护。

管理员执行旧本机账户关注迁移时，`watchlist_credit_hold` 随 `watchlist_item.user_id` 变更归属目标账户。迁移服务随后锁定目标账户锚点，基于目标账户的**最终**关注数计算所需锁定数；若目标账户现有积分总额不足，追加恰好覆盖差额的 `MIGRATION_GRANT`，再补齐或释放锁定关系。这样不会删除不重复关注，也不会把旧账户历史积分流水改写为目标账户流水。

### 7.2 API 与权限

| 接口 | 身份/权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/watchlist` | `WATCHLIST_SELF_READ` | 在既有分页响应中追加当前会话用户的 `quota`。 |
| `POST /api/v1/watchlist` | `WATCHLIST_SELF_WRITE` | 插入新关注后在同一事务检查并锁定额度；积分不足返回 `409/WATCHLIST_QUOTA_EXCEEDED`。重复添加保持幂等且不再占用积分。 |
| `DELETE /api/v1/watchlist/{fundCode}` | `WATCHLIST_SELF_WRITE` | 删除后在同一事务释放该项或冗余锁定积分；重复删除保持幂等。 |
| `POST /api/v1/admin/users/{userId}/watchlist-credits` | 仅 `SYSTEM_ADMIN` | 请求 `{amount, reason}`；只允许对启用的非历史账户发放正数积分，返回更新后的额度。 |

`JdbcWatchlistService` 继续负责基金存在校验和关注记录；`JdbcWatchlistCreditService` 只负责积分流水、锁定关系、额度计算和审计。FastAPI、市场同步范围、共享 Redis 基金读模型与个人持仓不参与积分逻辑。
