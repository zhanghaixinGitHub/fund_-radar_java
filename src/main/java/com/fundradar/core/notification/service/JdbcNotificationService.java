package com.fundradar.core.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.notification.api.NotificationPageResponse;
import com.fundradar.core.notification.api.NotificationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** NotificationService 的 JDBC 实现，以规则所属用户作为唯一数据范围边界。 */
@Service
public class JdbcNotificationService implements NotificationService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcNotificationService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    private static final String FILTERED_FEED = """
            WITH messages AS (
                SELECT n.notification_id AS id, r.fund_code, r.rule_type AS kind,
                       CASE WHEN r.rule_type = 'EVENT' THEN 'events' ELSE 'signals' END AS category,
                       NULL::bigint AS revision, NULL::text AS lifecycle, n.status = 'READ' AS is_read,
                       n.created_at AS occurred_at, n.created_at AS sent_at, n.payload
                FROM notification n JOIN alert_rule r ON r.rule_id = n.rule_id
                WHERE r.user_id = :userId
                UNION ALL
                SELECT review.notice_id, review.fund_code, review.kind,
                       CASE WHEN review.kind = 'FUND_PUBLICATION' THEN 'events'
                            WHEN review.kind IN ('SIGNAL_CHANGE', 'RISK_LEVEL') THEN 'signals'
                            WHEN review.kind IN ('TAKE_PROFIT', 'HOLDING_LOSS', 'DRAWDOWN') THEN 'thresholds'
                            ELSE 'holding-checks' END,
                       review.revision, review.lifecycle, review.read_revision = review.revision,
                       review.updated_at, review.created_at, review.payload
                FROM review_notice review
                WHERE review.user_id = :userId
            ), filtered AS (
                SELECT * FROM messages
                WHERE (:category = 'messages' OR category = :category)
                  AND (:fund = '' OR coalesce(fund_code, 'account') = :fund)
                  AND (NOT :unread OR NOT is_read)
                  AND (NOT :todayOnly OR (
                      sent_at >= (date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai')
                      AND sent_at < ((date_trunc('day', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') + INTERVAL '1 day') AT TIME ZONE 'Asia/Shanghai')
                  ))
            )
            """;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedPage feed(String category, String fund, boolean unread, int page, int pageSize) {
        return feed(category, fund, unread, page, pageSize, false, false);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedPage feed(String category, String fund, boolean unread, int page, int pageSize, boolean sentOrder, boolean todayOnly) {
        var filters = Map.of("userId", CurrentUserContext.require().userId(),
                "category", category, "fund", fund, "unread", unread, "todayOnly", todayOnly);
        long total = jdbcClient.sql(FILTERED_FEED + "SELECT count(*) FROM filtered")
                .params(filters).query(Long.class).single();
        int actualPage = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
        var items = jdbcClient.sql(FILTERED_FEED + """
                SELECT id, fund_code, kind, category, revision, lifecycle, is_read, payload,
                       CASE WHEN :sentOrder THEN sent_at ELSE occurred_at END AS display_time
                FROM filtered ORDER BY display_time DESC, id DESC
                LIMIT :pageSize OFFSET :offset
                """).params(filters).param("sentOrder", sentOrder).param("pageSize", pageSize).param("offset", (long) (actualPage - 1) * pageSize)
                .query((row, index) -> mapFeedItem(row, "display_time")).list();
        return new FeedPage(items, actualPage, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MatrixPage matrix(String category, String fund, boolean unread, int page, int pageSize) {
        var filters = Map.of("userId", CurrentUserContext.require().userId(),
                "category", category, "fund", fund, "unread", unread, "todayOnly", true);
        long total = jdbcClient.sql(FILTERED_FEED + """
                SELECT count(*) FROM (SELECT fund_code FROM filtered GROUP BY fund_code) AS funds
                """).params(filters).query(Long.class).single();
        int actualPage = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
        var cells = jdbcClient.sql(FILTERED_FEED + """
                , funds AS (
                    SELECT fund_code, max(occurred_at) AS latest_at
                    FROM filtered GROUP BY fund_code
                    ORDER BY latest_at DESC, fund_code NULLS FIRST LIMIT :pageSize OFFSET :offset
                ), ranked AS (
                    SELECT f.*, funds.latest_at,
                           row_number() OVER (cell ORDER BY f.occurred_at DESC, f.id DESC) AS position,
                           count(*) OVER (cell) AS message_count,
                           count(*) FILTER (WHERE NOT f.is_read) OVER (cell) AS unread_count
                    FROM filtered f JOIN funds ON f.fund_code IS NOT DISTINCT FROM funds.fund_code
                    WINDOW cell AS (PARTITION BY f.fund_code, f.category)
                )
                SELECT * FROM ranked WHERE position=1 ORDER BY latest_at DESC, fund_code NULLS FIRST, category
                """).params(filters).param("pageSize", pageSize).param("offset", (long) (actualPage - 1) * pageSize)
                .query((row, index) -> new FeedCell(mapFeedItem(row, "occurred_at"), row.getLong("unread_count"), row.getLong("message_count"))).list();
        return new MatrixPage(cells, actualPage, total);
    }

    private FeedItem mapFeedItem(ResultSet row, String timeColumn) throws SQLException {
        return new FeedItem(row.getObject("id", UUID.class), row.getString("fund_code"),
                row.getString("kind"), row.getString("category"), row.getObject("revision", Long.class),
                row.getString("lifecycle"), row.getBoolean("is_read"),
                row.getTimestamp(timeColumn).toInstant(), readPayload(row));
    }

    private Map<String, Object> readPayload(ResultSet row) throws SQLException {
        try {
            return objectMapper.readValue(row.getString("payload"), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new SQLException("notification payload is not valid JSON", exception);
        }
    }

    @Override
    /** 查询当前用户分页通知，不读取其他用户规则或消息。 */
    public NotificationPageResponse listCurrentUserNotifications(int page, int pageSize) {
        AuthenticatedUser user = CurrentUserContext.require();
        long totalCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification notification
                        JOIN alert_rule rule ON rule.rule_id = notification.rule_id
                        WHERE rule.user_id = :userId
                        """)
                .param("userId", user.userId())
                .query(Long.class)
                .single();
        List<NotificationResponse> items = jdbcClient.sql("""
                        SELECT notification.notification_id, rule.fund_code, rule.rule_type,
                               notification.trigger_type, notification.trigger_ref, notification.status,
                               notification.payload, notification.created_at, notification.read_at
                        FROM notification notification
                        JOIN alert_rule rule ON rule.rule_id = notification.rule_id
                        WHERE rule.user_id = :userId
                        ORDER BY notification.created_at DESC, notification.notification_id DESC
                        LIMIT :pageSize OFFSET :offset
                        """)
                .param("userId", user.userId())
                .param("pageSize", pageSize)
                .param("offset", (long) (page - 1) * pageSize)
                .query((row, rowNumber) -> mapNotification(row))
                .list();
        int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        return new NotificationPageResponse(items, page, pageSize, totalCount, totalPages);
    }

    @Override
    @Transactional
    /** 幂等标记已读；更新条件包含当前用户规则范围，不存在或越权均返回相同 404。 */
    public NotificationResponse markCurrentUserNotificationRead(UUID notificationId) {
        AuthenticatedUser user = CurrentUserContext.require();
        int updated = jdbcClient.sql("""
                        UPDATE notification notification
                        SET status = 'READ', read_at = COALESCE(notification.read_at, CURRENT_TIMESTAMP)
                        FROM alert_rule rule
                        WHERE notification.rule_id = rule.rule_id
                          AND notification.notification_id = :notificationId
                          AND rule.user_id = :userId
                        """)
                .param("notificationId", notificationId)
                .param("userId", user.userId())
                .update();
        if (updated != 1) {
            throw new NotificationNotFoundException();
        }
        return jdbcClient.sql("""
                        SELECT notification.notification_id, rule.fund_code, rule.rule_type,
                               notification.trigger_type, notification.trigger_ref, notification.status,
                               notification.payload, notification.created_at, notification.read_at
                        FROM notification notification
                        JOIN alert_rule rule ON rule.rule_id = notification.rule_id
                        WHERE notification.notification_id = :notificationId
                          AND rule.user_id = :userId
                        """)
                .param("notificationId", notificationId)
                .param("userId", user.userId())
                .query((row, rowNumber) -> mapNotification(row))
                .optional()
                .orElseThrow(NotificationNotFoundException::new);
    }

    /** 映射通知存储字段，并将数据库 JSON 转为受控的 JSON 节点。 */
    private NotificationResponse mapNotification(ResultSet row) throws SQLException {
        return new NotificationResponse(
                row.getObject("notification_id", UUID.class),
                row.getString("fund_code"),
                row.getString("rule_type"),
                row.getString("trigger_type"),
                row.getString("trigger_ref"),
                row.getString("status"),
                readPayload(row),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("read_at") == null ? null : row.getTimestamp("read_at").toInstant()
        );
    }
}
