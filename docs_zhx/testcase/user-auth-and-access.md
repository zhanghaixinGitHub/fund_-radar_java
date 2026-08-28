# 用户认证与权限测试用例

| 编号 | 场景 | 前置条件 | 操作 | 期望 |
| --- | --- | --- | --- | --- |
| AUTH-01 | 未登录门禁 | 无 Cookie | `GET /api/v1/funds` | `401/AUTHENTICATION_REQUIRED` |
| AUTH-02 | 手机号格式 | 无 Cookie | 使用非大陆 11 位号码登录 | `400/VALIDATION_ERROR`，不创建账户 |
| AUTH-03 | 未注册手机号登录 | 新手机号、6 至 20 位密码 | `POST /auth/login` | `401/INVALID_CREDENTIALS`，不创建账户 |
| AUTH-04 | 显式注册 | 新手机号、两次一致的 6 至 20 位密码 | `POST /auth/register` | 创建 `FUND_USER`、返回掩码手机号、下发两个 Cookie |
| AUTH-05 | 重复注册与登录 | 已注册活动账户 | 再次注册后再用正确密码登录 | 注册 `409/ACCOUNT_ALREADY_EXISTS`；登录成功并生成新会话 |
| AUTH-06 | CSRF | 已登录 | 缺少/错误 CSRF 的写请求 | `403/ACCESS_DENIED` |
| AUTH-07 | 本人数据隔离 | 用户 A/B 均有数据 | A 调用关注、提醒、持仓接口 | 仅出现 A 的记录；请求无 userId 参数 |
| AUTH-08 | 数据运营边界 | 数据运营已登录 | 调用 `/api/v1/admin/users` | `403/ACCESS_DENIED` |
| AUTH-09 | 系统管理员 | 系统管理员已登录 | 修改角色、重置密码、查看指定持仓 | 成功、目标会话撤销、审计不含密码/手机号 |
| AUTH-10 | 历史迁移 | 旧账户有关注，目标活动 | 未确认/确认后分别迁移 | 未确认拒绝；确认后仅迁移不重复关注，不迁移提醒/持仓 |
| AUTH-11 | 密码规则 | 无 Cookie | 注册、管理员创建、人工重置分别输入 5、6、20、21 位及纯空白密码 | 仅 6 至 20 位且非纯空白密码通过 |

---

## AUTH-12｜免费额度、积分锁定与幂等

前置条件：启用的基金用户已拥有 5 条不同有效关注、管理员已向该用户发放 2 个试用关注积分，目标基金均能由内部基金读模型校验。

操作步骤：

1. 用户请求 `GET /api/v1/watchlist`，记录 `quota`。
2. 依次新增第 6、7 条关注，再重复新增第 7 条。
3. 新增第 8 条关注；随后依次取消原本未锁定的一条和已锁定的一条关注。
4. 重新读取 `quota` 与关注页。

数据库验证：

```sql
SELECT entry_type, credit_delta, source_key, watchlist_item_id
FROM watchlist_credit_ledger
WHERE user_id = :user_id
ORDER BY created_at, credit_ledger_id;

SELECT hold.watchlist_item_id
FROM watchlist_credit_hold hold
JOIN watchlist_item item ON item.watchlist_item_id = hold.watchlist_item_id
WHERE item.user_id = :user_id;
```

| 项目 | 预期值 |
| --- | --- |
| 第 6、7 条关注 | 成功；分别锁定积分，总锁定为 2。 |
| 重复新增 | 成功且幂等；不新增关注或锁定流水。 |
| 第 8 条关注 | `409/WATCHLIST_QUOTA_EXCEEDED`，不写入关注记录。 |
| 任意取消后 | 当前锁定数等于 `max(0, 当前关注数 - 5)`，多余锁定自动释放。 |
| 审计 | 有新增、额度锁定/释放或拒绝所需的非敏感审计，不包含手机号或凭证。 |

---

## AUTH-13｜管理员发放与存量关注迁移

前置条件：系统管理员、数据运营、普通用户各有活动会话；另有一个活动用户在 V9 前已有 `N > 5` 条关注。

操作步骤：

1. 应用 V9 后查询存量用户的关注、积分流水和锁定关系；重复执行等价迁移检查不得产生第二次迁移发放。
2. 系统管理员对启用的非历史用户提交 `{amount: 3, reason: "试用扩容"}`。
3. 数据运营与普通用户调用同一管理员接口；系统管理员对停用或历史账户发放积分。
4. 准备旧本机账户的非重复关注和一个已有部分关注的活动目标账户，管理员确认后执行历史关注迁移。

数据库验证：

```sql
SELECT user_id, credit_delta, entry_type, source_key
FROM watchlist_credit_ledger
WHERE entry_type = 'MIGRATION_GRANT'
ORDER BY user_id;
```

| 项目 | 预期值 |
| --- | --- |
| 存量迁移 | 原关注数不变；每个 `N > 5` 用户恰有一条数量为 `N - 5` 的迁移发放，锁定数为 `N - 5`。 |
| 管理员发放 | 成功并返回额度；流水记录操作人 UUID、数量和原因，不记录手机号。 |
| 非系统管理员 | `403/ACCESS_DENIED`。 |
| 无效目标或金额 | `400/VALIDATION_ERROR`，不写积分流水。 |
| 并发安全 | 对同一用户并行新增关注与发放后，积分可用数不为负，锁定数不大于积分总额。 |
| 历史关注迁移 | 不重复的历史关注全部保留并转至目标账户；目标账户最终超额数量大于现有积分时，仅补发差额 `MIGRATION_GRANT`，锁定数等于 `max(0, 最终关注数 - 5)`。 |

---

## AUTH-14｜系统管理员查看积分流水

前置条件：活动账户已有至少一条管理员发放或迁移积分流水；系统管理员、数据运营和普通用户各有活动会话。

操作步骤：

1. 系统管理员请求 `GET /api/v1/admin/users/{userId}/watchlist-credit-ledger?page=0&pageSize=20`。
2. 核对返回顺序、类型、积分变动、原因、操作人显示名和时间；关闭前端明细后重新进入。
3. 数据运营和普通用户请求同一接口；系统管理员请求不存在用户或非法分页参数。

数据库验证：

```sql
SELECT entry_type, credit_delta, reason, actor_id, created_at
FROM watchlist_credit_ledger
WHERE user_id = :target_user_id
ORDER BY created_at DESC, credit_ledger_id DESC;
```

| 项目 | 预期值 |
| --- | --- |
| 系统管理员 | 返回与查询一致的分页流水；响应不包含手机号、用户 UUID、基金代码或 `actor_id`。 |
| 前端明细 | 仅在当前用户管理页显示；关闭后组件内存清空。 |
| 数据运营/普通用户 | `403/ACCESS_DENIED`。 |
| 读取审计 | 成功查看新增 `WATCHLIST_CREDIT_LEDGER_VIEWED`，记录操作管理员与目标内部标识。 |
| 非法目标/分页 | 不返回流水，分别为稳定的 `400` 错误响应。 |

## 自动化验证

1. 使用项目 `.tools/jdk17/jdk-17.0.20.1+1` 执行 `mvn test`，覆盖密码策略、未知手机号登录不建号、显式注册、重复注册、令牌摘要、管理员全权限、上下文拒绝和手机号脱敏。
2. Vue 执行 `npm run lint`、`npm run type-check`、`npm run build`，覆盖路由守卫和 API 类型构建。
3. 不在自动化测试中使用真实管理员手机号、密码或真实用户持仓；浏览器人工冒烟前在受保护环境配置首位管理员账户。
4. 对 V9 的数据库验证使用隔离测试用户或事务回滚；不得为测试删除、修改或暴露真实用户的关注和积分流水。
