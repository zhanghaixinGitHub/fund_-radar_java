package com.fundradar.core.auth.controller;

import com.fundradar.core.auth.AuthProperties;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.api.CurrentUserResponse;
import com.fundradar.core.auth.api.LoginRequest;
import com.fundradar.core.auth.api.RegisterRequest;
import com.fundradar.core.auth.api.UpdateProfileRequest;
import com.fundradar.core.auth.service.AccountService;
import com.fundradar.core.auth.service.SessionTokenSupport;
import com.fundradar.core.auth.web.CookieSupport;
import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 浏览器会话认证接口。
 *
 * <p>关联文档：docs_zhx/requirements/fund-radar.md；
 * docs_zhx/design/fund-radar.md；docs_zhx/testcase/fund-radar.md。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AccountService accountService;
    private final AuthProperties authProperties;

    public AuthController(AccountService accountService, AuthProperties authProperties) {
        this.accountService = accountService;
        this.authProperties = authProperties;
    }

    /** 已注册账户登录成功后下发 HttpOnly 会话 Cookie 和配对的 CSRF Cookie，仅返回公开用户资料。 */
    @PostMapping("/login")
    public ApiResponse<CurrentUserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        AccountService.LoginSession session = accountService.login(request.mobile(), request.password());
        addSessionCookies(response, session.rawToken());
        return ApiResponse.success(CurrentUserResponse.from(session.user()));
    }

    /** 显式注册默认基金用户并建立会话；姓名仅用于账户展示，账户创建不再隐藏在登录请求中。 */
    @PostMapping("/register")
    public ApiResponse<CurrentUserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response
    ) {
        AccountService.LoginSession session = accountService.register(request.mobile(), request.password(), request.displayName());
        addSessionCookies(response, session.rawToken());
        return ApiResponse.success(CurrentUserResponse.from(session.user()));
    }

    /** 读取由认证拦截器恢复的当前用户资料，用于浏览器刷新后的登录态恢复。 */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser() {
        return ApiResponse.success(CurrentUserResponse.from(CurrentUserContext.require()));
    }

    /** 当前登录用户仅可更新自己的公开姓名；手机号、角色与权限均不允许由本接口修改。 */
    @PutMapping("/me/profile")
    public ApiResponse<CurrentUserResponse> updateCurrentProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(accountService.updateCurrentProfile(CurrentUserContext.require(), request.displayName()));
    }

    /** 撤销当前服务端会话并立即过期两个浏览器 Cookie。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        accountService.logout(
                CookieSupport.findValue(request, authProperties.getSessionCookieName()),
                CurrentUserContext.require()
        );
        expireCookies(response);
        return ApiResponse.success(null);
    }

    private void addSessionCookies(HttpServletResponse response, String sessionToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(authProperties.getSessionCookieName(), sessionToken)
                .httpOnly(true)
                .secure(authProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(authProperties.getSessionTtl())
                .build()
                .toString());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(authProperties.getCsrfCookieName(), SessionTokenSupport.createToken())
                .httpOnly(false)
                .secure(authProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(authProperties.getSessionTtl())
                .build()
                .toString());
    }

    private void expireCookies(HttpServletResponse response) {
        addExpiredCookie(response, authProperties.getSessionCookieName(), true);
        addExpiredCookie(response, authProperties.getCsrfCookieName(), false);
    }

    private void addExpiredCookie(HttpServletResponse response, String cookieName, boolean httpOnly) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(cookieName, "")
                .httpOnly(httpOnly)
                .secure(authProperties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build()
                .toString());
    }
}
