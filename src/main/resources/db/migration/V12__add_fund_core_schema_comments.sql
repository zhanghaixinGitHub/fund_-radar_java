-- 为 fund_core 的现有表和字段补充中文业务注释。
-- 本迁移仅写 PostgreSQL COMMENT 元数据，不修改任何业务数据、约束、索引或权限。

DO $$
DECLARE
    table_comments JSONB;
    column_comments JSONB;
    table_record RECORD;
    column_record RECORD;
    table_comment_text TEXT;
    column_comment_text TEXT;
    actual_table_count INTEGER;
    expected_table_count INTEGER;
    actual_column_count INTEGER;
    commented_column_count INTEGER := 0;
BEGIN
    SELECT jsonb_object_agg(table_name, comment_text)
    INTO table_comments
    FROM (
        VALUES
            ('alert_rule', '用户提醒规则'),
            ('analysis_delivery_checkpoint', '分析结果投递消费检查点'),
            ('audit_log', '关键业务操作审计日志'),
            ('auth_session', '用户认证会话'),
            ('flyway_schema_history', 'Flyway 数据库迁移历史'),
            ('notification', '用户提醒通知'),
            ('portfolio_holding_snapshot', '持仓明细快照'),
            ('portfolio_snapshot', '用户确认的持仓快照'),
            ('role_permission', '系统角色与权限关联'),
            ('signal_snapshot', '基金分析信号快照'),
            ('system_permission', '系统权限目录'),
            ('system_role', '系统角色目录'),
            ('user_account', '用户账户'),
            ('watchlist_credit_account', '用户关注额度账户'),
            ('watchlist_credit_hold', '关注条目额度占用记录'),
            ('watchlist_credit_ledger', '用户关注额度流水'),
            ('watchlist_item', '用户关注基金')
    ) AS expected(table_name, comment_text);

    SELECT jsonb_object_agg(column_name, comment_text)
    INTO column_comments
    FROM (
        VALUES
            ('action', '审计操作名称'),
            ('actor', '操作主体标识'),
            ('actor_id', '额度流水操作人标识'),
            ('analysis_run_id', '分析任务运行唯一标识'),
            ('as_of_date', '数据或分析结果截至日期'),
            ('audit_log_id', '审计日志唯一标识'),
            ('checksum', '迁移脚本校验和'),
            ('cooldown_hours', '同一规则两次触发的最小间隔小时数'),
            ('consumer_name', '分析结果消费方名称'),
            ('created_at', '记录创建时间'),
            ('credit_delta', '额度变动值'),
            ('credit_ledger_id', '额度流水唯一标识'),
            ('data_as_of_date', '持仓数据所属日期'),
            ('data_as_of_status', '持仓数据日期是否已确认的状态'),
            ('deduplication_key', '通知去重键'),
            ('description', '迁移版本描述'),
            ('detail', '审计详情（JSON）'),
            ('display_name', '用户展示名称'),
            ('enabled', '是否启用'),
            ('entry_type', '额度流水类型'),
            ('execution_time', '迁移执行耗时（毫秒）'),
            ('expires_at', '认证会话失效时间'),
            ('feature_version', '特征计算规则版本'),
            ('fund_code', '基金代码'),
            ('fund_name', '基金名称'),
            ('fund_type', '基金类型快照'),
            ('holding_snapshot_id', '持仓明细快照唯一标识'),
            ('imported_at', '持仓快照导入时间'),
            ('installed_by', '执行迁移的数据库用户'),
            ('installed_on', '迁移安装时间'),
            ('installed_rank', '迁移安装顺序'),
            ('last_forecast_id', '上次已投递评分结果唯一标识'),
            ('last_scored_at', '上次已投递评分结果的完成时间'),
            ('last_seen_at', '认证会话最后访问时间'),
            ('last_triggered_at', '提醒规则最近一次触发时间'),
            ('mobile', '登录手机号或历史兼容标识'),
            ('model_version', '分析模型版本'),
            ('module_code', '权限所属模块代码'),
            ('notification_id', '通知唯一标识'),
            ('occurred_at', '审计操作发生时间'),
            ('password_hash', '密码哈希值'),
            ('payload', '信号或通知业务载荷（JSON）'),
            ('payload_hash', '信号载荷内容哈希'),
            ('permission_code', '权限代码'),
            ('permission_name', '权限名称'),
            ('portfolio_snapshot_id', '持仓快照唯一标识'),
            ('read_at', '通知已读时间'),
            ('reason', '额度变动原因'),
            ('received_at', '信号接收时间'),
            ('reported_amount', '来源展示的持仓金额'),
            ('reported_cumulative_gain_amount', '来源展示的累计收益金额'),
            ('reported_daily_gain_amount', '来源展示的当日收益金额'),
            ('reported_holding_gain_amount', '来源展示的持有收益金额'),
            ('reported_holding_gain_pct', '来源展示的持有收益率（百分比）'),
            ('reported_weight_pct', '来源展示的持仓占比（百分比）'),
            ('role', '用户所属角色代码'),
            ('role_code', '系统角色代码'),
            ('role_name', '系统角色名称'),
            ('rule_id', '提醒规则唯一标识'),
            ('rule_type', '提醒规则类型'),
            ('script', '迁移脚本名称'),
            ('session_id', '认证会话唯一标识'),
            ('signal_id', '基金分析信号唯一标识'),
            ('snapshot_id', '持仓快照唯一标识'),
            ('source_content_hash', '持仓来源内容哈希'),
            ('source_description', '持仓来源说明'),
            ('source_key', '额度流水幂等来源键'),
            ('source_kind', '持仓来源类型'),
            ('status', '当前业务或处理状态（取值范围见本表约束）'),
            ('success', '迁移是否执行成功'),
            ('target_id', '审计目标对象标识'),
            ('threshold', '提醒规则阈值'),
            ('token_hash', '认证 Token 哈希值'),
            ('trace_id', '请求链路追踪标识'),
            ('trigger_ref', '通知触发来源引用'),
            ('trigger_type', '通知触发类型'),
            ('type', '迁移类型'),
            ('updated_at', '记录最后更新时间'),
            ('user_id', '用户唯一标识'),
            ('version', '迁移版本号'),
            ('watchlist_item_id', '关注条目唯一标识')
    ) AS expected(column_name, comment_text);

    SELECT count(*)
    INTO expected_table_count
    FROM jsonb_object_keys(table_comments);

    SELECT count(*)
    INTO actual_table_count
    FROM pg_class AS relation
    JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = current_schema()
      AND relation.relkind IN ('r', 'p');

    IF actual_table_count <> expected_table_count THEN
        RAISE EXCEPTION
            'fund_core table comment coverage mismatch: actual_table_count=%, expected_table_count=%',
            actual_table_count,
            expected_table_count;
    END IF;

    SELECT count(*)
    INTO actual_column_count
    FROM pg_class AS relation
    JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    JOIN pg_attribute AS attribute ON attribute.attrelid = relation.oid
    WHERE namespace.nspname = current_schema()
      AND relation.relkind IN ('r', 'p')
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped;

    FOR table_record IN
        SELECT relation.relname AS table_name
        FROM pg_class AS relation
        JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = current_schema()
          AND relation.relkind IN ('r', 'p')
        ORDER BY relation.relname
    LOOP
        table_comment_text := table_comments ->> table_record.table_name;
        IF table_comment_text IS NULL THEN
            RAISE EXCEPTION 'missing table comment for fund_core table: %', table_record.table_name;
        END IF;

        EXECUTE format(
            'COMMENT ON TABLE %I.%I IS %L',
            current_schema(),
            table_record.table_name,
            table_comment_text
        );

        FOR column_record IN
            SELECT attribute.attname AS column_name
            FROM pg_attribute AS attribute
            JOIN pg_class AS relation ON relation.oid = attribute.attrelid
            JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = current_schema()
              AND relation.relname = table_record.table_name
              AND attribute.attnum > 0
              AND NOT attribute.attisdropped
            ORDER BY attribute.attnum
        LOOP
            column_comment_text := column_comments ->> column_record.column_name;
            IF column_comment_text IS NULL THEN
                RAISE EXCEPTION
                    'missing column comment for fund_core column: %.%',
                    table_record.table_name,
                    column_record.column_name;
            END IF;

            EXECUTE format(
                'COMMENT ON COLUMN %I.%I.%I IS %L',
                current_schema(),
                table_record.table_name,
                column_record.column_name,
                column_comment_text
            );
            commented_column_count := commented_column_count + 1;
        END LOOP;
    END LOOP;

    IF commented_column_count <> actual_column_count THEN
        RAISE EXCEPTION
            'fund_core column comment coverage mismatch: commented_column_count=%, actual_column_count=%',
            commented_column_count,
            actual_column_count;
    END IF;
END $$;
