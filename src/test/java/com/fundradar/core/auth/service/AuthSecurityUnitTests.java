package com.fundradar.core.auth.service;

import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.auth.api.CurrentUserResponse;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证密码强度与不透明会话令牌的关键安全边界。 */
class AuthSecurityUnitTests {

    @Test
    void acceptsPasswordBetweenSixAndTwentyCharactersWithoutCategoryRequirement() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("abcdef"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("abcdefghijklmnopqrst"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("abc 123"));
    }

    @Test
    void rejectsPasswordsOutsideLengthBoundaryOrWithOnlyWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("abcde"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("abcdefghijklmnopqrstu"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("      "));
    }

    @Test
    void generatesRandomTokensAndStableOneWayHashes() {
        String firstToken = SessionTokenSupport.createToken();
        String secondToken = SessionTokenSupport.createToken();

        assertNotEquals(firstToken, secondToken);
        assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(SessionTokenSupport.sha256(firstToken), SessionTokenSupport.sha256(firstToken));
        assertEquals(64, SessionTokenSupport.sha256(firstToken).length());
    }

    @Test
    void permitsSystemAdministratorOnlyInAdministratorContext() {
        AuthenticatedUser administrator = new AuthenticatedUser(
                UUID.randomUUID(), "administrator", "系统管理员", AccountRole.SYSTEM_ADMIN,
                Set.of(PermissionCode.USER_ACCOUNT_MANAGE)
        );
        CurrentUserContext.set(administrator);

        try {
            assertSame(administrator, CurrentUserContext.requireAdministrator());
        } finally {
            CurrentUserContext.clear();
        }
    }

    @Test
    void rejectsNonAdministratorInAdministratorContext() {
        AuthenticatedUser operator = new AuthenticatedUser(
                UUID.randomUUID(), "operator", "数据运营", AccountRole.DATA_OPERATOR,
                Set.of(PermissionCode.SYNC_JOB_START)
        );
        CurrentUserContext.set(operator);

        try {
            assertThrows(AccessDeniedException.class, CurrentUserContext::requireAdministrator);
        } finally {
            CurrentUserContext.clear();
        }
    }

    @Test
    void systemAdministratorHasCurrentAndFuturePermissionCodesWithoutDatabaseFallback() {
        AuthenticatedUser administrator = new AuthenticatedUser(
                UUID.randomUUID(), "13912345678", "系统管理员", AccountRole.SYSTEM_ADMIN, Set.of()
        );

        assertTrue(administrator.hasPermission(PermissionCode.USER_ACCOUNT_MANAGE));
        assertTrue(administrator.hasPermission(PermissionCode.PORTFOLIO_USER_READ));
    }

    @Test
    void currentUserResponseMasksMobileAndDoesNotReturnRawIdentifier() {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), "13912345678", "基金用户", AccountRole.FUND_USER, Set.of(PermissionCode.FUND_READ)
        );

        CurrentUserResponse response = CurrentUserResponse.from(user);

        assertEquals("139****5678", response.mobileMasked());
        assertNotEquals(user.mobile(), response.mobileMasked());
    }
}
