package com.fundradar.core.auth.service;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthProperties;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.LegacyAccount;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.auth.api.AdminUserPageResponse;
import com.fundradar.core.auth.api.AdminUserResponse;
import com.fundradar.core.auth.api.CreateUserRequest;
import com.fundradar.core.auth.api.CurrentUserResponse;
import com.fundradar.core.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 账户、会话和后台用户管理的 JDBC 服务。
 *
 * <p>手机号只作为登录标识，尚未接入短信验证时不能视为已核验实名信息。会话令牌仅以摘要方式持久化；
 * 关注、提醒和持仓等业务服务只能从认证上下文取得用户标识，禁止由浏览器提交 userId 作为数据范围。</p>
 */
@Service
public class AccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountService.class);
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    public AccountService(JdbcClient jdbcClient, PasswordEncoder passwordEncoder, AuthProperties authProperties) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    /** 验证已注册手机号和密码并创建新会话；未知手机号不会触发账户创建。 */
    @Transactional
    public LoginSession login(String requestedMobile, String password) {
        String mobile = normalizeMobile(requestedMobile);
        AccountCredential credential = findCredential(mobile).orElseThrow(InvalidCredentialsException::new);
        if (!ACTIVE.equals(credential.status())
                || credential.passwordHash() == null
                || !passwordEncoder.matches(password, credential.passwordHash())) {
            LOGGER.warn("AccountService.login   >>> login rejected");
            throw new InvalidCredentialsException();
        }
        return createSession(credential, "LOGIN_SUCCEEDED");
    }

    /** 显式注册默认基金用户并建立新会话；手机号唯一约束保证并发提交只会成功一次。 */
    @Transactional
    public LoginSession register(String requestedMobile, String password, String requestedDisplayName) {
        String mobile = normalizeMobile(requestedMobile);
        AccountCredential credential = registerFundUser(mobile, password, requestedDisplayName);
        return createSession(credential, "USER_REGISTERED");
    }

    /** 为已认证成功的账户创建会话，并只记录账户 UUID 与动作摘要。 */
    private LoginSession createSession(AccountCredential credential, String auditAction) {
        jdbcClient.sql("DELETE FROM auth_session WHERE expires_at <= CURRENT_TIMESTAMP").update();
        String rawToken = SessionTokenSupport.createToken();
        jdbcClient.sql("""
                        INSERT INTO auth_session (session_id, token_hash, user_id, expires_at)
                        VALUES (:sessionId, :tokenHash, :userId, :expiresAt)
                        """)
                .param("sessionId", UUID.randomUUID())
                .param("tokenHash", SessionTokenSupport.sha256(rawToken))
                .param("userId", credential.userId())
                .param("expiresAt", Timestamp.from(Instant.now().plus(authProperties.getSessionTtl())))
                .update();
        AuthenticatedUser user = toAuthenticatedUser(
                credential.userId(), credential.mobile(), credential.displayName(), credential.role()
        );
        writeAudit(user.userId().toString(), auditAction, user.userId().toString());
        LOGGER.info("AccountService.createSession   >>> session created, userId={}, role={}, action={}",
                user.userId(), user.role(), auditAction);
        return new LoginSession(user, rawToken);
    }

    /** 根据浏览器 HttpOnly Cookie 中的原始令牌恢复活动用户身份。 */
    public AuthenticatedUser resolveSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthenticationRequiredException();
        }
        SessionIdentity session = jdbcClient.sql("""
                        SELECT session_id, user_id, mobile, display_name, role
                        FROM auth_session
                        JOIN user_account USING (user_id)
                        WHERE token_hash = :tokenHash
                          AND expires_at > CURRENT_TIMESTAMP
                          AND status = 'ACTIVE'
                        """)
                .param("tokenHash", SessionTokenSupport.sha256(rawToken))
                .query((row, rowNumber) -> new SessionIdentity(
                        row.getObject("session_id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getString("mobile"),
                        row.getString("display_name"),
                        AccountRole.valueOf(row.getString("role"))
                ))
                .optional()
                .orElseThrow(AuthenticationRequiredException::new);
        jdbcClient.sql("""
                        UPDATE auth_session
                        SET last_seen_at = CURRENT_TIMESTAMP
                        WHERE session_id = :sessionId
                          AND last_seen_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                        """)
                .param("sessionId", session.sessionId())
                .update();
        return toAuthenticatedUser(session.userId(), session.mobile(), session.displayName(), session.role());
    }

    /** 撤销当前服务端会话；原始令牌只用于本次删除，不记录到日志或数据库明细。 */
    @Transactional
    public void logout(String rawToken, AuthenticatedUser user) {
        int deleted = rawToken == null ? 0 : jdbcClient.sql("DELETE FROM auth_session WHERE token_hash = :tokenHash")
                .param("tokenHash", SessionTokenSupport.sha256(rawToken))
                .update();
        writeAudit(user.userId().toString(), "LOGOUT", user.userId().toString());
        LOGGER.info("AccountService.logout   >>> userId={}, sessionRemoved={}", user.userId(), deleted == 1);
    }

    /** 当前会话只能更新自身姓名；角色、手机号和权限边界均由其他受控流程维护。 */
    @Transactional
    public CurrentUserResponse updateCurrentProfile(AuthenticatedUser user, String requestedDisplayName) {
        String displayName = normalizeDisplayName(requestedDisplayName);
        int updated = jdbcClient.sql("""
                        UPDATE user_account
                        SET display_name = :displayName, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId
                        """)
                .param("displayName", displayName)
                .param("userId", user.userId())
                .update();
        if (updated != 1) {
            throw new AuthenticationRequiredException();
        }
        writeAudit(user.userId().toString(), "USER_PROFILE_UPDATED", user.userId().toString());
        LOGGER.info("AccountService.updateCurrentProfile   >>> userId={}", user.userId());
        return CurrentUserResponse.from(toAuthenticatedUser(
                user.userId(), user.mobile(), displayName, user.role()
        ));
    }

    /** 按页返回后台账户清单和每个用户的关注数，避免应用层 N+1 聚合。 */
    public AdminUserPageResponse listUsers(int page, int pageSize) {
        long total = jdbcClient.sql("SELECT COUNT(*) FROM user_account").query(Long.class).single();
        List<AdminUserResponse> users = jdbcClient.sql("""
                        SELECT account.user_id, account.mobile, account.display_name, account.status, account.role,
                               account.created_at, COUNT(watchlist.watchlist_item_id) AS watchlist_count
                        FROM user_account account
                        LEFT JOIN watchlist_item watchlist ON watchlist.user_id = account.user_id
                        GROUP BY account.user_id, account.mobile, account.display_name, account.status, account.role,
                                 account.created_at
                        ORDER BY account.created_at DESC, account.user_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", pageSize)
                .param("offset", page * pageSize)
                .query((row, rowNumber) -> new AdminUserResponse(
                        row.getObject("user_id", UUID.class),
                        maskMobile(row.getString("mobile")),
                        row.getString("display_name"),
                        row.getString("status"),
                        AccountRole.valueOf(row.getString("role")),
                        row.getLong("watchlist_count"),
                        row.getTimestamp("created_at").toInstant(),
                        LegacyAccount.USER_ID.equals(row.getObject("user_id", UUID.class))
                ))
                .list();
        return new AdminUserPageResponse(users, total, page, pageSize);
    }

    /** 管理员可主动创建账号并指定角色；自助注册始终为基金用户。 */
    @Transactional
    public CurrentUserResponse createUser(CreateUserRequest request, AuthenticatedUser actor) {
        AuthenticatedUser user = createAccount(
                request.mobile(), request.displayName(), request.password(), request.role(), ACTIVE
        );
        writeAudit(actor.userId().toString(), "USER_CREATED", user.userId().toString());
        LOGGER.info("AccountService.createUser   >>> actorId={}, createdUserId={}, role={}",
                actor.userId(), user.userId(), user.role());
        return CurrentUserResponse.from(user);
    }

    /** 启用或停用账户；停用时立即撤销该账户所有已签发会话。 */
    @Transactional
    public void updateUserStatus(UUID userId, String status, AuthenticatedUser actor) {
        if (!ACTIVE.equals(status) && !DISABLED.equals(status)) {
            throw new IllegalArgumentException("账户状态不合法。");
        }
        AccountCredential target = findCredentialByUserId(userId).orElseThrow(() -> new IllegalArgumentException("目标用户不存在。"));
        rejectLegacyAccountChange(target);
        if (actor.userId().equals(userId) && DISABLED.equals(status)) {
            throw new IllegalArgumentException("不能停用当前登录的管理员账户。");
        }
        if (target.role() == AccountRole.SYSTEM_ADMIN && DISABLED.equals(status) && isLastActiveAdministrator(userId)) {
            throw new IllegalArgumentException("不能停用最后一个启用的系统管理员。");
        }
        jdbcClient.sql("""
                        UPDATE user_account
                        SET status = :status, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId
                        """)
                .param("status", status)
                .param("userId", userId)
                .update();
        if (DISABLED.equals(status)) {
            jdbcClient.sql("DELETE FROM auth_session WHERE user_id = :userId")
                    .param("userId", userId)
                    .update();
        }
        writeAudit(actor.userId().toString(), "USER_STATUS_UPDATED", userId + ":" + status);
        LOGGER.info("AccountService.updateUserStatus   >>> actorId={}, targetUserId={}, status={}",
                actor.userId(), userId, status);
    }

    /** 调整目标账户角色；系统管理员在代码层自动拥有所有当前和后续权限。 */
    @Transactional
    public void updateUserRole(UUID userId, AccountRole role, AuthenticatedUser actor) {
        if (role == null) {
            throw new IllegalArgumentException("账户角色不能为空。");
        }
        AccountCredential target = findCredentialByUserId(userId).orElseThrow(() -> new IllegalArgumentException("目标用户不存在。"));
        rejectLegacyAccountChange(target);
        if (target.role() == AccountRole.SYSTEM_ADMIN && role != AccountRole.SYSTEM_ADMIN && isLastActiveAdministrator(userId)) {
            throw new IllegalArgumentException("不能移除最后一个启用的系统管理员角色。");
        }
        jdbcClient.sql("""
                        UPDATE user_account
                        SET role = :role, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId
                        """)
                .param("role", role.name())
                .param("userId", userId)
                .update();
        jdbcClient.sql("DELETE FROM auth_session WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        writeAudit(actor.userId().toString(), "USER_ROLE_UPDATED", userId + ":" + role.name());
        LOGGER.info("AccountService.updateUserRole   >>> actorId={}, targetUserId={}, role={}",
                actor.userId(), userId, role);
    }

    /** 系统管理员人工重置密码，并撤销目标账号的全部既有会话。 */
    @Transactional
    public void resetPassword(UUID userId, String newPassword, AuthenticatedUser actor) {
        AccountCredential target = findCredentialByUserId(userId).orElseThrow(() -> new IllegalArgumentException("目标用户不存在。"));
        rejectLegacyAccountChange(target);
        PasswordPolicy.validate(newPassword);
        jdbcClient.sql("""
                        UPDATE user_account
                        SET password_hash = :passwordHash, updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId
                        """)
                .param("passwordHash", passwordEncoder.encode(newPassword))
                .param("userId", userId)
                .update();
        jdbcClient.sql("DELETE FROM auth_session WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        writeAudit(actor.userId().toString(), "USER_PASSWORD_RESET", userId.toString());
        LOGGER.info("AccountService.resetPassword   >>> actorId={}, targetUserId={}", actor.userId(), userId);
    }

    /**
     * 将历史固定本机用户的关注记录迁移至已确认的目标用户。
     * 提醒规则和持仓快照属于更敏感数据，保留待单独确认归属，不在本操作中修改。
     */
    @Transactional
    public int transferLegacyWatchlist(UUID targetUserId, AuthenticatedUser actor) {
        if (LegacyAccount.USER_ID.equals(targetUserId) || !isActiveAccount(targetUserId)) {
            throw new IllegalArgumentException("目标必须是已启用的非历史用户。");
        }
        jdbcClient.sql("""
                        DELETE FROM watchlist_item legacy_item
                        USING watchlist_item target_item
                        WHERE legacy_item.user_id = :legacyUserId
                          AND target_item.user_id = :targetUserId
                          AND legacy_item.fund_code = target_item.fund_code
                        """)
                .param("legacyUserId", LegacyAccount.USER_ID)
                .param("targetUserId", targetUserId)
                .update();
        int transferred = jdbcClient.sql("""
                        UPDATE watchlist_item
                        SET user_id = :targetUserId
                        WHERE user_id = :legacyUserId
                        """)
                .param("legacyUserId", LegacyAccount.USER_ID)
                .param("targetUserId", targetUserId)
                .update();
        writeAudit(actor.userId().toString(), "LEGACY_WATCHLIST_TRANSFERRED", targetUserId.toString());
        LOGGER.info("AccountService.transferLegacyWatchlist   >>> actorId={}, targetUserId={}, transferred={}",
                actor.userId(), targetUserId, transferred);
        return transferred;
    }

    /** 在没有系统管理员时，从受保护环境变量创建一次首位管理员；缺失配置时不记录敏感值。 */
    @Transactional
    public void bootstrapAdministratorIfRequired() {
        long administratorCount = jdbcClient.sql("SELECT COUNT(*) FROM user_account WHERE role = 'SYSTEM_ADMIN'")
                .query(Long.class)
                .single();
        if (administratorCount > 0) {
            return;
        }
        if (isBlank(authProperties.getInitialAdminMobile()) || isBlank(authProperties.getInitialAdminPassword())) {
            LOGGER.warn("AccountService.bootstrapAdministratorIfRequired   >>> no administrator exists; "
                    + "configure FUND_AUTH_INITIAL_ADMIN_MOBILE and FUND_AUTH_INITIAL_ADMIN_PASSWORD before login");
            return;
        }
        try {
            AuthenticatedUser administrator = createAccount(
                    authProperties.getInitialAdminMobile(),
                    authProperties.getInitialAdminDisplayName(),
                    authProperties.getInitialAdminPassword(),
                    AccountRole.SYSTEM_ADMIN,
                    ACTIVE
            );
            writeAudit("bootstrap", "ADMIN_BOOTSTRAPPED", administrator.userId().toString());
            LOGGER.info("AccountService.bootstrapAdministratorIfRequired   >>> administrator created, userId={}",
                    administrator.userId());
        } catch (IllegalArgumentException exception) {
            LOGGER.error("AccountService.bootstrapAdministratorIfRequired   >>> bootstrap administrator was not created: {}",
                    exception.getMessage());
        }
    }

    /** 显式注册默认基金用户；冲突不进入异常事务状态，而是稳定返回重复注册错误。 */
    private AccountCredential registerFundUser(String mobile, String password, String requestedDisplayName) {
        PasswordPolicy.validate(password);
        UUID userId = UUID.randomUUID();
        String displayName = normalizeDisplayName(requestedDisplayName);
        String passwordHash = passwordEncoder.encode(password);
        int created = jdbcClient.sql("""
                        INSERT INTO user_account (user_id, mobile, display_name, password_hash, role, status)
                        VALUES (:userId, :mobile, :displayName, :passwordHash, :role, :status)
                        ON CONFLICT (mobile) DO NOTHING
                        """)
                .param("userId", userId)
                .param("mobile", mobile)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .param("role", AccountRole.FUND_USER.name())
                .param("status", ACTIVE)
                .update();
        if (created == 0) {
            throw new AccountAlreadyExistsException();
        }
        LOGGER.info("AccountService.registerFundUser   >>> account created, userId={}", userId);
        return new AccountCredential(userId, mobile, displayName, passwordHash, AccountRole.FUND_USER, ACTIVE);
    }

    /** 创建管理员主动维护的账户；密码先完成策略校验再交给 BCrypt 处理。 */
    private AuthenticatedUser createAccount(
            String requestedMobile,
            String requestedDisplayName,
            String password,
            AccountRole role,
            String status
    ) {
        String mobile = normalizeMobile(requestedMobile);
        String displayName = normalizeDisplayName(requestedDisplayName);
        if (role == null) {
            throw new IllegalArgumentException("账户角色不能为空。");
        }
        if (findCredential(mobile).isPresent()) {
            throw new IllegalArgumentException("手机号已存在。");
        }
        PasswordPolicy.validate(password);
        UUID userId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO user_account (user_id, mobile, display_name, password_hash, role, status)
                        VALUES (:userId, :mobile, :displayName, :passwordHash, :role, :status)
                        """)
                .param("userId", userId)
                .param("mobile", mobile)
                .param("displayName", displayName)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("role", role.name())
                .param("status", status)
                .update();
        return toAuthenticatedUser(userId, mobile, displayName, role);
    }

    /** 按手机号查询认证所需的最小列，不查询或暴露会话令牌。 */
    private Optional<AccountCredential> findCredential(String mobile) {
        return jdbcClient.sql("""
                        SELECT user_id, mobile, display_name, password_hash, role, status
                        FROM user_account
                        WHERE mobile = :mobile
                        """)
                .param("mobile", mobile)
                .query((row, rowNumber) -> mapCredential(row))
                .optional();
    }

    private Optional<AccountCredential> findCredentialByUserId(UUID userId) {
        return jdbcClient.sql("""
                        SELECT user_id, mobile, display_name, password_hash, role, status
                        FROM user_account
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query((row, rowNumber) -> mapCredential(row))
                .optional();
    }

    /** 查询目标账户是否为可承接历史关注的活动账号。 */
    private boolean isActiveAccount(UUID userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_account
                        WHERE user_id = :userId AND status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() == 1;
    }

    private boolean isLastActiveAdministrator(UUID userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM user_account
                        WHERE role = 'SYSTEM_ADMIN'
                          AND status = 'ACTIVE'
                          AND user_id <> :userId
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() == 0;
    }

    private void rejectLegacyAccountChange(AccountCredential target) {
        if (LegacyAccount.USER_ID.equals(target.userId())) {
            throw new IllegalArgumentException("历史本机账户仅用于确认关注迁移，不能启用或修改。");
        }
    }

    /** 按数据库角色权限矩阵加载权限；系统管理员在代码层自动拥有所有当前和后续权限。 */
    private Set<PermissionCode> findPermissions(AccountRole role) {
        if (role == AccountRole.SYSTEM_ADMIN) {
            return Set.copyOf(EnumSet.allOf(PermissionCode.class));
        }
        EnumSet<PermissionCode> permissions = EnumSet.noneOf(PermissionCode.class);
        for (String code : jdbcClient.sql("""
                        SELECT permission_code
                        FROM role_permission
                        WHERE role_code = :roleCode
                        ORDER BY permission_code
                        """)
                .param("roleCode", role.name())
                .query(String.class)
                .list()) {
            try {
                permissions.add(PermissionCode.valueOf(code));
            } catch (IllegalArgumentException exception) {
                LOGGER.error("AccountService.findPermissions   >>> unknown permission code in database, role={}", role);
                return Set.of();
            }
        }
        return Set.copyOf(permissions);
    }

    /** 从账户最小身份字段和服务端权限矩阵创建当前请求身份。 */
    private AuthenticatedUser toAuthenticatedUser(
            UUID userId,
            String mobile,
            String displayName,
            AccountRole role
    ) {
        return new AuthenticatedUser(userId, mobile, displayName, role, findPermissions(role));
    }

    /** 记录账户级审计，不写入手机号、密码、Cookie、CSRF 值或请求体。 */
    private void writeAudit(String actor, String action, String targetId) {
        jdbcClient.sql("""
                        INSERT INTO audit_log (audit_log_id, trace_id, actor, action, target_id)
                        VALUES (:auditId, :traceId, :actor, :action, :targetId)
                        """)
                .param("auditId", UUID.randomUUID())
                .param("traceId", TraceContext.getTraceId())
                .param("actor", actor)
                .param("action", action)
                .param("targetId", targetId)
                .update();
    }

    /** 限定中国大陆 11 位手机号，且不接受空格、国际区号或其他号码格式。 */
    private String normalizeMobile(String mobile) {
        if (mobile == null || !mobile.matches("^1[3-9]\\d{9}$")) {
            throw new IllegalArgumentException("请输入中国大陆 11 位手机号。");
        }
        return mobile;
    }

    /** 去除展示名首尾空白并限制数据库字段上限。 */
    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("显示名称不能为空。");
        }
        String normalized = displayName.strip();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("显示名称不能超过 128 个字符。");
        }
        return normalized;
    }

    private String maskMobile(String mobile) {
        if (mobile == null || !mobile.matches("^1[3-9]\\d{9}$")) {
            return "历史账户";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 认证查询专用行对象，避免密码哈希进入 API 响应模型。 */
    private AccountCredential mapCredential(ResultSet row) throws SQLException {
        return new AccountCredential(
                row.getObject("user_id", UUID.class),
                row.getString("mobile"),
                row.getString("display_name"),
                row.getString("password_hash"),
                AccountRole.valueOf(row.getString("role")),
                row.getString("status")
        );
    }

    /** 单次登录创建成功后返回的身份与仅供 Set-Cookie 使用的原始令牌。 */
    public record LoginSession(AuthenticatedUser user, String rawToken) {
    }

    private record AccountCredential(
            UUID userId,
            String mobile,
            String displayName,
            String passwordHash,
            AccountRole role,
            String status
    ) {
    }

    private record SessionIdentity(
            UUID sessionId,
            UUID userId,
            String mobile,
            String displayName,
            AccountRole role
    ) {
    }
}
