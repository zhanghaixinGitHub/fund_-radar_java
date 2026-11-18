# 统一为当前“我的持仓”

用户于2026-11-17明确：截图是录入资料的方式，不代表独立实际账户。业务仅有当前我的持仓。

## 变更

- 资金安排、持仓检查、条件比较取消前台范围选择，接口省略scope时默认当前账本；旧两种scope均兼容到当前账本。
- 风险检查从SimulationRepository.positions动态读取正份额持仓，不再查询portfolio_snapshot。估值缺失保留基金，账户总额和整体损失保持未知。
- account_funding_preference原行不修改、不删除、不迁移。两种来源统一分页，按confirmed_at、原revision、scope及记录ID稳定排序；最后撤销不会回退到另一来源的有效设置。
- 对外版本为合并历史序号，写入受本人锁保护，保存到既有SIMULATED存储标识，物理版本从本人全部旧版本最大值递增；保留账号隔离、版本冲突和重复请求核验。
- 条件比较使用同一账本和同一资金安排；日期提醒仅检查当前安排，旧独立范围的活动提醒撤回，历史消息保留。
- 旧截图、期初余额来源、订单、定投、收益及数据卷不清理、不重建。内部sim命名继续沿用，不把模拟交易描述成真实渠道成交。

## 验证边界

AccountRiskTests、AccountOptionsTests、UnifiedHoldingsReadTests通过。后者核对旧链接同源、正份额过滤、缺失估值不冒充完整账户，且不访问旧截图表。

在PostgreSQL会话临时表中用实际仓储SQL验证两类历史合并、末次撤销、账号隔离、分页和合并后追加；事务回滚，永久资金安排行数不变。脚本在02-java/.local-runs/unified-holdings/check_funding_sql.py。不运行会自动变更正式库的集成测试。既有AccountFundingIntegrationTests的旧双范围断言已改，但本轮未运行该集成套件。

前端类型、改动文件ESLint通过。构建与受控服务更新另记执行结果；浏览器画面未验收。未开展投资偏好及资金安排参与综合建议的下一阶段。

执行结果：根dev.ps1 Build通过；ReviewNoticeConditionTests补充通过，总计15项相关单元测试通过。临时表追加SQL验证通过；只读核对未转入期初账本的历史持仓为0。随后通过dev.ps1 Restart更新三端，Status三端运行及健康检查通过。未修改正式资金安排、订单、持仓、定投或收益；服务继续沿用既有业务授权。浏览器实际画面和已登录账号端到端保存未验收。
