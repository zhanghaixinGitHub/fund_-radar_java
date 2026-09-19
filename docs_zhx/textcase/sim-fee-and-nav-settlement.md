# 模拟交易：申赎费率与净值公布即结算 — 测试用例

> 关联需求：docs_zhx/requirements/sim-fee-and-nav-settlement.md

## TC-01｜净值公布当晚即确认订单，估值推进到最新净值日（9-18 场景复现）

- **前置条件**：
  - 用户已有 9-17 及以前已确认的持仓与估值；
  - 存在 9-18（周五）10:00 定投生成的 PENDING 买入订单（trade_date=2026-09-18，eligible_date=2026-09-21）；
  - 上游已入库 9-18 净值（nav_daily 中 ann_date=2026-09-18）。
- **场景**：验证净值公布后，不再等待确认日 9-21，当晚结算即确认订单并更新 9-18 估值。
- **操作步骤**：
  1. 等待结算任务执行（每分钟 tick，或触发管理员手动补录）；
  2. 打开"模拟组合"页面，查看持仓与"最近一期收益"。
- **数据库验证**：
  ```sql
  SELECT order_id,status,rule_version,confirmed_at FROM sim_order
   WHERE fund_code = '<测试基金>' AND trade_date = '2026-09-18';
  SELECT valuation_date,daily_gain FROM sim_daily_valuation
   WHERE fund_code = '<测试基金>' AND valuation_date >= '2026-09-17' ORDER BY valuation_date;
  ```
  | 字段 | 预期值 |
  |---|---|
  | sim_order.status | CONFIRMED |
  | sim_order.rule_version | CN_NAV_SIM_V2_FEE |
  | sim_daily_valuation.valuation_date | 含 2026-09-18（不再停在 09-17） |
  | sim_daily_valuation.daily_gain(09-18) | 非空，与支付宝 9-18 收益同方向 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-02｜早期订单净值未公布时不冻结后续估值

- **前置条件**：用户持仓某基金；存在一笔 PENDING 买入订单（trade_date=2026-09-21），其净值尚未公布。
- **场景**：验证订单待确认期间，已持有仓位的每日估值照常推进，不因订单未确认而冻结。
- **操作步骤**：
  1. 等待结算任务执行；
  2. 查看持仓估值日期与最近一期收益。
- **数据库验证**：
  ```sql
  SELECT status FROM sim_order WHERE fund_code='<测试基金>' AND trade_date='2026-09-21';
  SELECT (snapshot->>'navDate') AS nav_date FROM sim_position
   WHERE fund_code='<测试基金>' AND user_id='<测试用户>';
  ```
  | 字段 | 预期值 |
  |---|---|
  | sim_order.status | PENDING（保持不变） |
  | sim_position.navDate | 最新已公布净值日（不因订单未确认而停在上一个交易日） |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-03｜申购费扣减（A 类，费率 0.08%）

- **前置条件**：费率表已初始化，测试基金存在 PURCHASE 规则 rate=0.0008；
  ```sql
  INSERT INTO sim_fee_rule(fund_code,fund_name,fee_type,rate,data_source,effective_from)
  VALUES ('001021','华夏亚债中国指数A','PURCHASE',0.0008,'MANUAL','2026-01-01');
  ```
- **场景**：验证买入份额按净申购金额换算，申购费从申购金额中扣减。
- **操作步骤**：
  1. 用测试用户下一笔 100.00 元的买入订单（15:00 前）；
  2. 等待结算确认；
  3. 在订单执行详情查看费用与份额。
- **数据库验证**：
  ```sql
  SELECT execution->>'fee' AS fee, execution->>'netAmount' AS net, execution->>'shares' AS shares
   FROM sim_order WHERE fund_code='001021' AND side='BUY' ORDER BY created_at DESC LIMIT 1;
  ```
  | 字段 | 预期值 |
  |---|---|
  | execution.fee | ≈ 0.08 |
  | execution.netAmount | ≈ 99.92 |
  | execution.shares | ≈ 99.92 ÷ 当日净值 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-04｜赎回费分档（C 类：7 天内 1.5%、7~30 天 0.5%、30 天以上 0）

- **前置条件**：费率表存在该基金 REDEEM 三档规则（0~6 天 1.5%、7~29 天 0.5%、≥30 天 0）；用户持有确认满 3 天（自然日）的批次。
- **场景**：验证分批持有天数分别计档扣费。
- **操作步骤**：
  1. 用户卖出一部分持有 3 天的份额（15:00 前下单）；
  2. 等待结算确认；
  3. 查看订单执行详情与持仓收益变化。
- **数据库验证**：
  ```sql
  SELECT execution->>'grossAmount' AS gross, execution->>'fee' AS fee,
         execution->>'netAmount' AS net, execution->>'realizedGain' AS gain
   FROM sim_order WHERE fund_code='<测试基金>' AND side='SELL' ORDER BY created_at DESC LIMIT 1;
  ```
  | 字段 | 预期值 |
  |---|---|
  | execution.fee | = gross × 1.5%（保留 2 位小数） |
  | execution.netAmount | = gross − fee |
  | execution.realizedGain | = net − 释放成本 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-05｜份额 T+1 可用：确认当晚不可卖、次日可卖

- **前置条件**：用户 9-18 买入并已于当晚确认（TC-01 状态）；9-18 是周五，下一交易日为 9-21。
- **场景**：验证确认不等于可卖，份额到 T+1 交易日才释放为可用。
- **操作步骤**：
  1. 9-18 晚（净值公布后）尝试下单卖出该基金全部可用份额；
  2. 9-21 开盘后再尝试卖出；
  3. 观察两次下单结果。
- **数据库验证**：
  ```sql
  SELECT snapshot->>'shares' AS shares, snapshot->>'availableShares' AS available
   FROM sim_position WHERE fund_code='<测试基金>' AND user_id='<测试用户>';
  ```
  | 字段 | 预期值 |
  |---|---|
  | 9-18 晚 availableShares | 不含 9-18 买入份额（下单卖出被拒绝或只可卖旧份额） |
  | 9-21 availableShares | 含 9-18 买入份额 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-06｜历史已确认订单重算不变（V1 兼容）

- **前置条件**：存在 9-17 及以前已确认的 V1 订单与 9-17 前的估值曲线（可先备份查询结果）。
- **场景**：验证新规则上线重放后，历史成交与收益曲线保持不变。
- **操作步骤**：
  1. 记录升级前 9-17 及以前的订单 execution 与 sim_daily_valuation 数据；
  2. 执行数据迁移与结算重算；
  3. 对比升级前后数据。
- **数据库验证**：
  ```sql
  SELECT order_id,execution,rule_version FROM sim_order
   WHERE status='CONFIRMED' AND trade_date < '2026-09-18';
  SELECT valuation_date,market_value,cumulative_gain,daily_gain FROM sim_daily_valuation
   WHERE valuation_date < '2026-09-18';
  ```
  | 字段 | 预期值 |
  |---|---|
  | 订单 execution / rule_version | 与升级前完全一致（仍为 V1） |
  | sim_daily_valuation（< 09-18） | 与升级前完全一致 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-07｜后台费率维护：修改费率后新订单生效

- **前置条件**：admin 账号登录；费率表存在测试基金 PURCHASE 规则。
- **场景**：验证后台可维护费率，修改后新订单按新费率计算，历史订单不受影响。
- **操作步骤**：
  1. 后台打开"模拟费率维护"页，搜索测试基金；
  2. 修改申购费率为 0.0010，保存；
  3. 下一笔 100 元买入订单，查看执行详情；
  4. 再查看 9-17 前的历史订单 execution，确认未变化。
- **数据库验证**：
  ```sql
  SELECT rate,version,data_source,updated_at FROM sim_fee_rule
   WHERE fund_code='<测试基金>' AND fee_type='PURCHASE';
  SELECT execution->>'fee' AS fee FROM sim_order
   WHERE fund_code='<测试基金>' AND side='BUY' ORDER BY created_at DESC LIMIT 1;
  ```
  | 字段 | 预期值 |
  |---|---|
  | sim_fee_rule.rate | 0.0010；version +1；data_source='MANUAL' |
  | 新订单 execution.fee | ≈ 0.10（100 × 0.1%） |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-08｜费率初始化抓取（天天基金）

- **前置条件**：模拟费率维护页可访问；天天基金 f10 页面可访问（外网）。
- **场景**：验证全量初始化与单基金刷新。
- **操作步骤**：
  1. 点击"全量初始化"按钮，观察进度与结果统计；
  2. 点击单基金"重新抓取"，观察该基金费率更新；
  3. 对比抓取结果与天天基金页面展示费率。
- **数据库验证**：
  ```sql
  SELECT fund_code,fee_type,min_days,rate,data_source FROM sim_fee_rule
   WHERE fund_code='001021' ORDER BY fee_type,min_days NULLS FIRST;
  ```
  | 字段 | 预期值 |
  |---|---|
  | 记录数 | 申购 1 档 + 赎回 N 档（按实际页面） |
  | data_source | EASTMONEY_F10 |
  | 费率值 | 与天天基金 jjfl 页面折后费率一致 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## 变更记录

### 2026-09-19｜初版：8 条用例覆盖净值公布即确认、估值解耦、申购费、赎回分档、T+1 可用、V1 兼容、后台维护、费率抓取

## TC-09｜后台费率维护：编辑生效费率与乐观锁冲突

- **前置条件**：SYSTEM_ADMIN 账号已登录（含 SIM_FEE_RULE_ADMIN 权限）；sim_fee_rule 中已有基金 000001 的 PURCHASE 规则（version=1，rate=0.015）。准备两个浏览器窗口 A、B 同时打开费率维护页。
- **场景**：管理员人工修改申购费率；两个管理员同时修改同一行时，后保存者被拒绝并提示刷新。
- **操作步骤**：
  1. 以 SYSTEM_ADMIN 登录，进入「后台 → 费率维护」，筛选基金代码 000001；
  2. 窗口 A 点击 000001 申购行的「编辑」，费率输入 `0.12`，点击「保存」；
  3. 窗口 B（仍显示 version=1 的旧数据）点击同一行「编辑」，费率输入 `0.13`，点击「保存」；
  4. 观察 B 窗口提示与列表刷新结果。
- **数据库验证**：
  ```sql
  SELECT rule_id, rate, version, updated_at FROM sim_fee_rule
  WHERE fund_code='000001' AND fee_type='PURCHASE' AND effective_to IS NULL;
  ```
  | 字段 | 预期值 |
  |---|---|
  | rate | 0.0012（窗口 A 的值） |
  | version | 2 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## TC-10｜费率抓取：单只刷新与全量初始化

- **前置条件**：SYSTEM_ADMIN 账号已登录；Java 与 Python 服务均已启动且 Python 可访问 fundf10.eastmoney.com；存在至少一只已登记基金（如 001021）出现在用户模拟持仓中。
- **场景**：通过天天基金 f10 抓取初始化/刷新费率；解析失败（如按年计期的基金）时提示人工维护且不影响其余基金。
- **操作步骤**：
  1. 进入「后台 → 费率维护」，在「抓取与初始化」区输入基金代码 `001021`，点击「单只刷新」；
  2. 观察提示「基金 001021 费率已按天天基金 f10 刷新」且列表定位到该基金；
  3. 点击「全量初始化」，等待完成提示；
  4. 模拟下单 100 元买入 001021，确认订单执行详情中费用为申购费。
- **数据库验证**：
  ```sql
  SELECT fee_type, min_days, max_days, rate, data_source, effective_to
  FROM sim_fee_rule WHERE fund_code='001021' AND effective_to IS NULL ORDER BY fee_type, min_days;
  ```
  | 字段 | 预期值 |
  |---|---|
  | PURCHASE 行 rate | 0.0008（jjfl 页面优惠费率 0.08%） |
  | REDEEM 行数 | 2 档：0~6 天 0.015、≥7 天 0 |
  | data_source | EASTMONEY_F10 |
- **测试结果**：
  - [ ] ✅ 通过
  - [ ] ❌ 未通过
  - **未通过原因**：（测试人员填写）

---

## 变更记录

### 2026-09-19｜初版：8 条用例覆盖净值公布即确认、估值解耦、申购费、赎回分档、T+1 可用、V1 兼容、后台维护、费率抓取

### 2026-09-19｜补充：TC-09 后台编辑乐观锁、TC-10 单只刷新与全量初始化
