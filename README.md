# 全市场基金雷达 Java 核心服务

Java 服务是前端唯一业务接口与审计边界。M0 提供健康检查和经 FastAPI 转发的 Mock 基金读模型；不连接真实基金数据、不执行交易。

## JDK

本机默认 JDK 为 8；本项目使用 `C:\ideaProject\workSpace12\.tools\jdk17\jdk-17.0.20.1+1` 中的 JDK 17。

```powershell
$env:JAVA_HOME = 'C:\ideaProject\workSpace12\.tools\jdk17\jdk-17.0.20.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
mvn spring-boot:run
```

项目根目录的 `.env` 已是唯一的本地运行配置，Spring Boot 会在启动时自动加载；无需复制文件或手工设置环境变量。

## M0 接口

- `GET /api/v1/health`：Java 服务健康检查。
- `GET /api/v1/system/ai-health`：经 Java 探测 FastAPI 内部健康接口。
- `GET /api/v1/funds`：M0 Mock 基金列表；字段为 camelCase。
- `GET /api/v1/funds/{fundCode}`：M0 Mock 基金详情；`dataSource=M0_MOCK` 明确表示不是实际数据。

关联文档：

- `docs_zhx/requirements/fund-radar.md`
- `docs_zhx/implementation/fund-radar.md`
- `docs_zhx/design/fund-radar-api-v1.md`
