package com.fundradar.core.auth.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/** 集中读取 Cookie，避免业务服务直接依赖 Servlet API。 */
public final class CookieSupport {

    private CookieSupport() {
    }

    public static String findValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
