package com.qianlong.cloud.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.qianlong.cloud.admin.service.AdminUserService;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;

/**
 * Sa-Token 权限数据源。
 *
 * <p><b>这是后端的安全边界。</b>前端 {@code authStore.can()} 只控制按钮可见性，
 * 任何人都能直接打接口 —— 所以 {@code @SaCheckPermission} 的判定必须落到数据库里的
 * 角色权限上，而不是信任前端传来的任何东西。
 *
 * <p>每次校验都查库（而不是把权限塞进 token）：这样**改权限立即生效**，
 * 不需要等 token 过期。代价是每次带权限注解的请求多一次查询，
 * 对一个后台管理系统完全可接受；对比「改了权限但旧 token 还能用」的越权风险，
 * 这个取舍是明确的。
 */
@Component
@RequiredArgsConstructor
public class SaTokenPermissionProvider implements StpInterface {

    private final AdminUserService adminUserService;

    /** 返回该账号拥有的权限键集合（超管为单元素 {@code *}） */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Integer userId = parseId(loginId);
        if (userId == null) {
            return List.of();
        }
        return adminUserService.permissionsOf(userId);
    }

    /** 返回该账号的角色 code 集合 */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Integer userId = parseId(loginId);
        if (userId == null) {
            return List.of();
        }
        return new ArrayList<>(adminUserService.roleCodesOf(userId));
    }

    private static Integer parseId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException ex) {
            // loginId 被塞了非数字内容：当作无权限处理，不抛异常（抛了会变成 500 噪音）
            return null;
        }
    }
}
