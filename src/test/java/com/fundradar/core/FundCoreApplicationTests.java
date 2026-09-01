package com.fundradar.core;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Java 核心服务的基础集成测试。
 *
 * 覆盖 Spring 上下文装配、提醒规则安全契约，以及禁止凭据采集和交易执行路由的 M5 安全门禁。
 */
@SpringBootTest
class FundCoreApplicationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    private MockMvc mockMvc;

    /** 基于完整 Spring Web 上下文初始化 MockMvc，供接口级断言复用。 */
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    /** 验证 Spring Boot 上下文能够成功装配。 */
    @Test
    void contextLoads() {
    }

    /** 验证未登录请求在业务参数校验前被认证拦截，不能借非法载荷绕过身份边界。 */
    @Test
    void rejectsUnauthenticatedAlertRuleBeforeRequestValidation() throws Exception {
        mockMvc.perform(put("/api/v1/alert-rules")
                        .contentType("application/json")
                        .content("""
                                {"fundCode":"000001","ruleType":"EVENT","threshold":0.5,"enabled":true}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    /** M3-05 分析控制与本人通知接口必须在进入业务逻辑前完成服务端认证。 */
    @Test
    void rejectsUnauthenticatedAnalysisControlAndNotificationRequests() throws Exception {
        mockMvc.perform(post("/api/v1/admin/analysis/runs/rolling-backtest")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/notifications/00000000-0000-0000-0000-000000000001/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    /** 登录没有 CSRF 前置条件，但来自非白名单 Origin 的请求必须在参数校验前被拒绝。 */
    @Test
    void rejectsLoginFromUnexpectedOriginBeforeAccountHandling() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", "https://unexpected.example")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    /** 登录不会再创建未知手机号；只有显式注册成功后，该手机号才能建立会话。 */
    @Test
    @Transactional
    void requiresExplicitRegistrationBeforeSignIn() throws Exception {
        String mobile = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String password = "123456";
        String displayName = "测试用户";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password
                                + "\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FUND_USER"))
                .andExpect(jsonPath("$.data.displayName").value(displayName));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password
                                + "\",\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("FUND_USER"))
                .andExpect(jsonPath("$.data.displayName").value(displayName));
    }

    /** 已认证用户携带匹配 CSRF 后只能更新自己的姓名，并立即取得更新后的公开资料。 */
    @Test
    @Transactional
    void updatesCurrentProfileDisplayNameWithValidSessionAndCsrf() throws Exception {
        String mobile = "137" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        String password = "123456";
        String originalDisplayName = "原姓名";
        String updatedDisplayName = "新姓名";

        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"mobile\":\"" + mobile + "\",\"password\":\"" + password
                                + "\",\"displayName\":\"" + originalDisplayName + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie sessionCookie = registration.getResponse().getCookie("fund_radar_session");
        Cookie csrfCookie = registration.getResponse().getCookie("fund_radar_csrf");
        Assertions.assertNotNull(sessionCookie);
        Assertions.assertNotNull(csrfCookie);

        mockMvc.perform(put("/api/v1/auth/me/profile")
                        .cookie(sessionCookie, csrfCookie)
                        .header("X-CSRF-Token", csrfCookie.getValue())
                        .contentType("application/json")
                        .content("{\"displayName\":\"" + updatedDisplayName + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value(updatedDisplayName))
                .andExpect(jsonPath("$.data.role").value("FUND_USER"));
    }

    /** 验证公开路由中不存在第三方凭据采集、支付或基金交易执行入口。 */
    @Test
    void exposesNoCredentialCaptureOrTransactionExecutionRoute() {
        boolean unsafeRouteExists = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(path -> path.contains("alipay")
                        || path.contains("cookie")
                        || path.contains("captcha")
                        || path.contains("trade")
                        || path.contains("buy")
                        || path.contains("sell")
                        || path.contains("redeem")
                        || path.contains("subscribe"));

        Assertions.assertFalse(unsafeRouteExists,
                "M5 safety gate forbids third-party credential-capture and transaction routes.");
    }
}
