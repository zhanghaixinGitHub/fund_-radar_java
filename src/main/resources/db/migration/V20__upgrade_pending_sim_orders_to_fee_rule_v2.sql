-- 费率口径切换（2026-09-18 起生效）：待结算模拟订单从 V1（不计费）升级为 V2（计申赎费）。
-- 背景：申赎费方案自 2026-09-18 起生效，此前下单尚未结算的订单也应按新口径结算；
--       已结算历史订单保持 V1 不动，保证历史回放口径不漂移。
-- 风险：批量 UPDATE sim_order 待结算行；WHERE 命中部分索引 ix_sim_order_pending(trade_date)
--       （status='PENDING' 且 trade_date>=2026-09-18），不会全表扫描；rule_version<>V2 保证幂等。
-- 回滚：UPDATE sim_order SET rule_version='CN_NAV_SIM_V1_NO_FEE'
--       WHERE status='PENDING' AND trade_date>=DATE '2026-09-18' AND rule_version='CN_NAV_SIM_V2_FEE';
UPDATE sim_order
   SET rule_version = 'CN_NAV_SIM_V2_FEE'
 WHERE status = 'PENDING'
   AND trade_date >= DATE '2026-09-18'
   AND rule_version <> 'CN_NAV_SIM_V2_FEE';
