package com.fundradar.core.auth.web;

import com.fundradar.core.auth.AuthProperties;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.auth.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 业务 API 的统一认证与 CSRF 拦截器。
 *
 * <p>登录和注册是仅有的匿名账户入口；其余 `/api/v1/**` 请求必须携带服务端会话。写请求还需匹配
 * 非 HttpOnly 的 CSRF Cookie 与请求头，且带 Origin 时必须命中 CORS 白名单。</p>
 */
@Component
public class AuthWebInterceptor implements HandlerInterceptor {

    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final AccountService accountService;
    private final AuthProperties authProperties;
    private final com.fundradar.core.config.WebCorsProperties webCorsProperties;

    public AuthWebInterceptor(
            AccountService accountService,
            AuthProperties authProperties,
            com.fundradar.core.config.WebCorsProperties webCorsProperties
    ) {
        this.accountService = accountService;
        this.authProperties = authProperties;
        this.webCorsProperties = webCorsProperties;
    }

    /** 恢复用户身份，并在变更请求前完成 Origin 与 CSRF 双重校验。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (isAnonymousAuthRequest(request)) {
            verifyOrigin(request);
            return true;
        }
        String sessionToken = CookieSupport.findValue(request, authProperties.getSessionCookieName());
        AuthenticatedUser user = accountService.resolveSession(sessionToken);
        if (isUnsafeMethod(request.getMethod())) {
            verifyOriginAndCsrf(request);
        }
        CurrentUserContext.set(user);
        return true;
    }

    /** 无论正常完成还是异常完成都清理线程身份，避免容器线程复用造成数据越权。 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        CurrentUserContext.clear();
    }

    private boolean isAnonymousAuthRequest(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        String requestUri = request.getRequestURI();
        String authBasePath = request.getContextPath() + "/api/v1/auth/";
        return requestUri.equals(authBasePath + "login") || requestUri.equals(authBasePath + "register");
    }

    private boolean isUnsafeMethod(String method) {
        return HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method)
                || HttpMethod.DELETE.matches(method);
    }

    private void verifyOriginAndCsrf(HttpServletRequest request) {
        verifyOrigin(request);
        String cookieToken = CookieSupport.findValue(request, authProperties.getCsrfCookieName());
        String headerToken = request.getHeader(CSRF_HEADER);
        if (!StringUtils.hasText(cookieToken) || !StringUtils.hasText(headerToken)
                || !MessageDigest.isEqual(
                        cookieToken.getBytes(StandardCharsets.UTF_8),
                        headerToken.getBytes(StandardCharsets.UTF_8)
                )) {
            throw new AccessDeniedException();
        }
    }

    /** 登录和注册不需要既有 CSRF Cookie，但带 Origin 的请求仍必须来自浏览器白名单。 */
    private void verifyOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (StringUtils.hasText(origin) && !webCorsProperties.getAllowedOrigin().equals(origin)) {
            throw new AccessDeniedException();
        }
    }
}
