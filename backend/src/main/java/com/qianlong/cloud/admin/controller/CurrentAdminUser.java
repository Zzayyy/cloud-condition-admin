package com.qianlong.cloud.admin.controller;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前登录的管理账号。
 *
 * <p>用法：{@code public ApiResult<?> foo(@CurrentAdminUser AdminPrincipal me)}。
 * 由 {@link CurrentAdminUserArgumentResolver} 从 Sa-Token 会话里取。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentAdminUser {
}
