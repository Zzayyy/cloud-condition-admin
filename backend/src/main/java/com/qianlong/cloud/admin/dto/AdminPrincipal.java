package com.qianlong.cloud.admin.dto;

import java.util.List;

/**
 * 当前登录人的精简身份（不落库、不进 token，每次从数据库读）。
 *
 * @param userId         账号 id
 * @param username       登录名
 * @param displayName    显示名
 * @param roleCodes      角色 code 集合
 * @param permissions    权限键集合（超管为 {@code ["*"]}）
 * @param mustChangePassword 是否需下次登录强制改密
 */
public record AdminPrincipal(
        Integer userId,
        String username,
        String displayName,
        List<String> roleCodes,
        List<String> permissions,
        boolean mustChangePassword) {
}
