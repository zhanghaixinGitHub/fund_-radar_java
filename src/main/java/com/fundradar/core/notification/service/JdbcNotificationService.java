package com.fundradar.core.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.notification.api.NotificationPageResponse;
import com.fundradar.core.notification.api.NotificationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
        try {
            return new NotificationResponse(
                    row.getObject("notification_id", UUID.class),
                    row.getString("fund_code"),
                    row.getString("rule_type"),
                    row.getString("trigger_type"),
                    row.getString("trigger_ref"),
                    row.getString("status"),
                    objectMapper.readTree(row.getString("payload")),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("read_at") == null ? null : row.getTimestamp("read_at").toInstant()
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("notification payload is not valid JSON", exception);
        }
    }
}
