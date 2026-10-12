package com.qianlong.cloud.admin.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码编码器。
 *
 * <p>只引入 {@code spring-security-crypto} 这一个 jar（不引入 Spring Security 自动配置），
 * 拿 BCrypt 用；认证与会话由 Sa-Token 负责。
 *
 * <p>BCrypt 自带随机 salt，同一条密码每次哈希结果都不同，所以「校验历史密码是否重复」
 * 只能逐个 {@code matches}，不能用哈希值相等来比较。
 */
@Component
public class PasswordEncoderHolder {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }
}
