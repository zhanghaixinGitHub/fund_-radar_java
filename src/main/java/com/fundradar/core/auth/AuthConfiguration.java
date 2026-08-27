package com.fundradar.core.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 认证域的密码哈希配置；只保存 BCrypt 哈希，禁止保存或记录明文密码。 */
@Configuration
public class AuthConfiguration {

    /** 创建可复用的 BCrypt 密码编码器。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
