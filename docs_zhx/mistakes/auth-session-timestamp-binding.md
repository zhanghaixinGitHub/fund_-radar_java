# 认证会话时间参数绑定

### 2026-08-27｜PostgreSQL JDBC 不能推断 `Instant` SQL 类型

场景：账户注册或登录成功后，服务端向 `auth_session.expires_at` 写入会话过期时间。

现象：账户创建成功后写会话返回 500，PostgreSQL JDBC 报无法推断 `java.time.Instant` 的 SQL 类型。

根因：`JdbcClient` 命名参数直接传入 `Instant`，当前 PostgreSQL 驱动未将其自动绑定为时间戳。

正确做法：在 JDBC 边界使用 `Timestamp.from(instant)` 显式绑定到 `TIMESTAMP` 列；接口与集成测试必须覆盖一次成功注册或成功登录后的会话创建。

关联代码：`auth/service/AccountService#createSession`、`FundCoreApplicationTests#requiresExplicitRegistrationBeforeSignIn`。

关联技术点：Spring JDBC、PostgreSQL、`TIMESTAMP`、会话持久化。
