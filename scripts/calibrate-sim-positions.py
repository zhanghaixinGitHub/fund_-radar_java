"""按确认截图持仓校准模拟持仓：为差额生成 MANUAL 买入订单，结算任务自动重放对齐市值。

用法:
    python scripts/calibrate-sim-positions.py > /tmp/calibrate.sql
    cat /tmp/calibrate.sql | docker exec -i fund-radar-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d fund_core -v ON_ERROR_STOP=1'

幂等: 订单 request_key 为 calibrate:<tradeDate>:<fundCode>，order_id 由其 uuid5 派生，重复执行全部 ON CONFLICT 跳过。
仅覆盖行情层支持的基金；QDII/黄金OTHER/场内E/持有期基金被 unsupported_reason 排除，不在此校准。
"""

import hashlib
import json
import subprocess
import sys
import uuid
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

USER_ID = "6a28062f-c4bc-486e-8551-14c7298f79cf"
SNAPSHOT_FILE = Path(__file__).resolve().parent.parent / "portfolio-snapshot.local.json"
# python 行情层 unsupported_reason 排除的基金（QDII、fund_type=OTHER、market=E、持有期）
UNSUPPORTED = {"018853", "019449", "008764", "014978", "021740", "160323", "013853"}
ELIGIBLE_DATE = "2026-09-18"  # trade_date 的下一交易日，须 > trade_date 且 <= 执行当天
NAMESPACE = uuid.UUID("23f63c82-cf89-469f-ac9a-1706f569025f")


def psql(db: str, sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "fund-radar-postgres-1", "sh", "-c",
         f'psql -U "$POSTGRES_USER" -d {db} -A -t -c "{sql}"'],
        capture_output=True, text=True, check=True, encoding="utf-8",
    )
    return result.stdout.strip()


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def main() -> None:
    holdings = json.loads(SNAPSHOT_FILE.read_text(encoding="utf-8"))["holdings"]
    positions = {}
    for line in psql("fund_core",
                     f"SELECT fund_code, snapshot->>'marketValue', snapshot->>'fundName', "
                     f"snapshot->'navDate'->>0, snapshot->'navDate'->>1, snapshot->'navDate'->>2 "
                     f"FROM sim_position WHERE user_id='{USER_ID}'").splitlines():
        code, mv, name, year, month, day = line.split("|")
        positions[code] = (Decimal(mv), name, (int(year), int(month), int(day)))

    statements = ["BEGIN;"]
    skipped, planned = [], []
    for holding in sorted(holdings, key=lambda h: h["fundCode"]):
        code = holding["fundCode"]
        if code in UNSUPPORTED:
            skipped.append(code)
            continue
        if code not in positions:
            raise SystemExit(f"{code} 无模拟持仓且不在排除清单，需先确认行情支持并插入空仓位 stub")
        current_mv, fund_name, (year, month, day) = positions[code]
        trade_date = f"{year:04d}-{month:02d}-{day:02d}"
        delta = (Decimal(str(holding["reportedAmount"])) - current_mv).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP)
        if delta < Decimal("0.01"):
            skipped.append(code)
            continue
        request_key = f"calibrate:{trade_date}:{code}"
        order_id = str(uuid.uuid5(NAMESPACE, request_key))
        amount_text = delta.normalize().to_eng_string() if delta == delta.to_integral() else str(delta)
        request_hash = sha256_text(json.dumps([code, "BUY", amount_text, "", False],
                                              ensure_ascii=False, separators=(",", ":")))
        year_i, month_i, day_i = int(year), int(month), int(day)
        eligible_parts = ELIGIBLE_DATE.split("-")
        payload = json.dumps({
            "orderId": order_id, "userId": USER_ID, "fundCode": code, "fundName": fund_name,
            "side": "BUY", "amount": str(delta), "shares": None,
            "tradeDate": [year_i, month_i, day_i],
            "eligibleDate": [int(eligible_parts[0]), int(eligible_parts[1]), int(eligible_parts[2])],
            "status": "PENDING", "sourceKind": "MANUAL", "requestKey": request_key,
            "requestHash": request_hash, "createdAt": 0, "confirmedAt": None, "execution": None,
        }, ensure_ascii=False, separators=(",", ":"))
        # createdAt 由数据库 now() 写入；payload 中的 createdAt 仅作流水展示，用 0 占位会与页面流水不一致，
        # 因此流水 payload 改用与 created_at 相同的 now() 文本无法预知——这里直接在 SQL 里用 to_epoch 生成。
        payload = payload.replace('"createdAt":0', '"createdAt":' + "__EPOCH__")
        revision = sha256_text(json.dumps(["ROOT", payload.replace("__EPOCH__", "0")],
                                          ensure_ascii=False, separators=(",", ":")))
        epoch_expr = "extract(epoch from now())"
        statements.append(
            f"INSERT INTO sim_order(order_id,user_id,fund_code,fund_name,side,amount,shares,trade_date,eligible_date,"
            f"status,source_kind,request_key,request_hash,created_at) VALUES "
            f"('{order_id}','{USER_ID}','{code}','{fund_name}','BUY',{delta},NULL,'{trade_date}','{ELIGIBLE_DATE}',"
            f"'PENDING','MANUAL','{request_key}','{request_hash}',now()) "
            f"ON CONFLICT (user_id,request_key) DO NOTHING;"
        )
        statements.append(
            f"INSERT INTO sim_ledger_entry(entry_id,user_id,fund_code,event_key,entry_type,payload,revision,created_at) "
            f"SELECT gen_random_uuid(),'{USER_ID}','{code}','submit:{order_id}','ORDER_SUBMITTED',"
            f"CAST(replace('{payload}'::text,'__EPOCH__',to_char({epoch_expr},'FM9999999990.000000000')) AS jsonb),"
            f"'{revision}',now() WHERE EXISTS (SELECT 1 FROM sim_order WHERE order_id='{order_id}') "
            f"ON CONFLICT DO NOTHING;"
        )
        statements.append(
            f"INSERT INTO audit_log(audit_log_id,trace_id,actor,action,target_id) "
            f"SELECT gen_random_uuid(),'','{USER_ID}','SIM_ORDER_CREATED','{order_id}' "
            f"WHERE EXISTS (SELECT 1 FROM sim_order WHERE order_id='{order_id}') "
            f"AND NOT EXISTS (SELECT 1 FROM audit_log WHERE action='SIM_ORDER_CREATED' AND target_id='{order_id}');"
        )
        planned.append((code, fund_name, str(delta), trade_date))
    statements.append("COMMIT;")
    print("\n".join(statements))
    for code, name, delta, trade_date in planned:
        print(f"-- PLANNED {code} {name} BUY {delta} @ {trade_date}", file=__import__("sys").stderr)
    if skipped:
        print(f"-- SKIPPED {','.join(skipped)}", file=__import__("sys").stderr)


if __name__ == "__main__":
    main()
