package com.qianlong.cloud.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.qianlong.cloud.admin.dto.SystemDtos.AdminRoleView;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserView;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginResult;
import com.qianlong.cloud.admin.dto.SystemDtos.ProfileResult;
import com.qianlong.cloud.admin.entity.AdminUserEntity;
import com.qianlong.cloud.admin.dto.AdminPrincipal;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 登录鉴权。
 *
 * <p>三条与前端约定死的规则（前端按 {@code code} 分派文案，不匹配 message）：
 * <ol>
 *   <li><b>账号不存在与密码错误同码</b>（{@code 40101}）：分开返回等于提供账号枚举接口；</li>
 *   <li><b>停用 / 锁定先于密码校验返回</b>：否则被锁的管理员会以为是密码错了，
 *       反复重试反而让情况更糟；</li>
 *   <li><b>失败要累计</b>，达到策略阈值自动锁定。</li>
 * </ol>
 *
 * <p>登录日志（{@code T_AdminLoginLog}）成功失败都写，且写日志失败不能影响登录结果 ——
 * 但会打 error 日志，不能静默。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserService userService;
    private final AdminRoleService roleService;
    private final LoginLogService loginLogService;
    private final AuditService auditService;

    /** 登录动作的审计键（登记型动作，没有对应权限点） */
    private static final String ACTION_LOGIN = "system:login";

    public LoginResult login(String username, String rawPassword, HttpServletRequest request) {
        String name = username == null ? "" : username.trim();
        String ip = AuditService.clientIp(request);
        String os = AuditService.clientOs(request);
        long start = System.currentTimeMillis();

        AdminUserEntity user;
        try {
            user = userService.authenticate(name, rawPassword);
        } catch (RuntimeException ex) {
            // 失败也要留痕：登录日志 + 审计。审计里绝不写密码。
            String reason = ex.getMessage() == null ? "登录失败" : ex.getMessage();
            loginLogService.record(name, false, reason, ip, os);
            auditService.failure(name, ACTION_LOGIN, name, reason, ip, start);
            throw ex;
        }

        userService.onLoginSuccess(user.getId(), ip);
        loginLogService.record(user.getUsername(), true, "登录成功", ip, os);
        auditService.success(user.getUsername(), ACTION_LOGIN, user.getUsername(), "登录成功", ip, start);

        // Sa-Token 登录：以账号 id 作为 loginId（登录名可改，id 不可改，会话更稳）
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();

        AdminPrincipal principal = userService.principalOf(user.getId());
        return new LoginResult(
                token,
                user.getUsername(),
                user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                principal == null ? List.<String>of() : principal.roleCodes(),
                principal == null ? List.<String>of() : principal.permissions(),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    /** 当前登录人的资料与权限键集合（前端路由守卫在「有 token 无身份」时调用） */
    public ProfileResult profile(Integer userId) {
        AdminPrincipal principal = userService.principalOf(userId);
        if (principal == null) {
            // 账号在会话有效期内被删除 → 直接踢掉，不能让已删除的账号继续用旧 token
            StpUtil.logout();
            throw new com.qianlong.cloud.admin.common.BizException(
                    com.qianlong.cloud.admin.common.ErrorCode.NOT_LOGIN,
                    "账号不存在或已被删除，请重新登录");
        }
        return new ProfileResult(
                principal.username(),
                principal.displayName() == null ? principal.username() : principal.displayName(),
                principal.roleCodes(),
                principal.permissions(),
                principal.mustChangePassword());
    }

    /**
     * 登出。
     *
     * <p>只注销当前这一个 token（{@code logout()} 而不是 {@code kickout(id)}）：
     * 运维常在跳板机与本地同时开着，在本地点登出不应该把跳板机的会话也踢掉。
     */
    public void logout(HttpServletRequest request) {
        String username = StpUtil.isLogin() ? String.valueOf(userService.principalOf(
                Integer.valueOf(String.valueOf(StpUtil.getLoginId()))).username()) : "unknown";
        loginLogService.record(username, true, "登出", AuditService.clientIp(request),
                AuditService.clientOs(request));
        StpUtil.logout();
    }

    /** 登录页下拉/调试用：可用角色清单（不含权限明细） */
    public List<AdminRoleView> rolesForLoginHints() {
        return roleService.listRoles();
    }

    /** 由账号 id 取账号视图（改密等接口返回最新状态用） */
    public AdminUserView userView(Integer userId) {
        return userService.getUser(userId);
    }
}
