# 全市场基金雷达 Java 核心服务

Java 服务是前端唯一业务接口与审计边界。它提供健康检查、经 FastAPI 转发的持久化基金目录读模型，以及本机单用户确认快照的只读查询；不执行交易。

## JDK

本机默认 JDK 为 8；本项目使用 `C:\ideaProject\workSpace12\.tools\jdk17\jdk-17.0.20.1+1` 中的 JDK 17。

```powershell
$env:JAVA_HOME = 'C:\ideaProject\workSpace12\.tools\jdk17\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
mvn spring-boot:run
```

项目根目录的 `.env` 是数据库和 Java → FastAPI 的本地运行配置，Spring Boot 会自动加载。Compose Redis 启用认证：启动 Java 服务时还需在当前会话或受控启动配置中提供 `FUND_REDIS_PASSWORD`（兼容 `REDIS_PASSWORD`）；密钥不写入源码、日志或接口响应。

## 当前接口

- `GET /api/v1/health`：Java 服务健康检查。
- `GET /api/v1/system/ai-health`：经 Java 探测 FastAPI 内部健康接口。
- `GET /api/v1/funds`：已持久化的基金目录样本列表；字段为 camelCase。当前仅有 6 条手工核验样本，未同步净值时 `asOfDate=null`。
- `GET /api/v1/funds/{fundCode}`：目录详情；`navStatus=NOT_SYNCED` 明确表示没有实时或历史净值。
- `POST /api/v1/funds/sync/focused-nav-incremental`：从详情页手动补齐六只重点基金净值；同步等待完成并返回新增、更新、跳过统计，不依赖 Celery Beat 或 Worker。
- `GET /api/v1/portfolio/current`：本机当前用户的确认持仓快照；只读，日期未知时返回 `dataAsOfStatus=UNKNOWN` 与 `dataAsOfDate=null`。
- `GET/POST /api/v1/watchlist`、`DELETE /api/v1/watchlist/{fundCode}`：M1 本机单用户关注；重复写入幂等并记录审计。

基金列表与详情的最后成功读模型会缓存到 Redis。FastAPI 不可用时，只有命中缓存才返回 `stale=true` 和 `cachedAt`；调用方必须明确展示陈旧状态。

人工同步调用 Python 的读取超时独立配置为 `AI_SERVICE_MANUAL_SYNC_READ_TIMEOUT`（默认 5 分钟），不会放宽普通读模型的 `AI_SERVICE_READ_TIMEOUT`（默认 3 秒）。若已有手动或定时同步运行，接口返回 `409/FOCUSED_SYNC_IN_PROGRESS`，前端应等待后重试。

关联文档：

- `docs_zhx/requirements/fund-radar.md`
- `docs_zhx/implementation/fund-radar.md`
- `docs_zhx/design/fund-radar-api-v1.md`
