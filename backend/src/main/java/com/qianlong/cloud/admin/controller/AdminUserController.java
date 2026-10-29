package com.qianlong.cloud.admin.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserStats;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminUserView;
import com.qianlong.cloud.admin.dto.SystemDtos.EnabledPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.ResetPasswordPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.UserQuery;
import com.qianlong.cloud.admin.service.AdminUserService;
import com.qianlong.cloud.admin.service.AuditService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 管理用户与权限接口（{@code /api/system/users}）。
 *
 * <p><b>读要求菜单权限 {@code menu:system-user}，写要求
 * {@code action:system-user:manage}</b>。「能改谁能进后台」是最高一档权限，
 * 写操作全部额外要求它；两者是 AND，菜单权限不隐式覆盖写权限。
 *
 * <p>每个写操作都写审计（成功与失败都写）：
 * 「谁给自己建了账号 / 谁把谁停用了 / 谁试过重置密码但失败了」都必须查得到。
 */
@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
public class AdminUserController {

    /** 菜单权限：管理用户与权限页 */
    private static final String MENU_SYSTEM_USER = "menu:system-user";

    private static final String ACTION_MANAGE = "action:system-user:manage";

    private final AdminUserService userService;
    private final AuditService auditService;

    /** 分页查询管理账号（关键字 / 角色 / 状态筛选，与前端列表一致） */
    @SaCheckPermission(MENU_SYSTEM_USER)
    @GetMapping
    public ApiResult<PageResult<AdminUserView>> list(UserQuery query) {
        return ApiResult.ok(userService.listUsers(query));
    }

    /** 单个账号详情 */
    @SaCheckPermission(MENU_SYSTEM_USER)
    @GetMapping("/{id}")
    public ApiResult<AdminUserView> detail(@PathVariable Integer id) {
        return ApiResult.ok(userService.getUser(id));
    }

    /**
     * 管理账号汇总（**全量口径**，卡片用）。
     *
     * <p>与 {@code GET /system/users} 列表同筛选条件，
     * {@code totalCount} 应等于列表 {@code total}。
     *
     * <p>路由注意：映射到 {@code /stats/summary}（两段），
     * 与 {@code /{id}}（一段）不冲突，不会被当成 id 解析。
     */
    @SaCheckPermission(MENU_SYSTEM_USER)
    @GetMapping("/stats/summary")
    public ApiResult<AdminUserStats> stats(UserQuery query) {
        return ApiResult.ok(userService.stats(query));
    }

    /** 新建账号 */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PostMapping
    public ApiResult<AdminUserView> create(@CurrentAdminUser AdminPrincipal me,
                                           @Valid @RequestBody AdminUserPayload payload,
                                           HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            AdminUserView created = userService.createUser(payload);
            auditService.success(me.username(), ACTION_MANAGE, created.username(),
                    "新建管理账号并分配角色 " + created.roleIds(), ip, start);
            return ApiResult.ok(created);
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, payload.username(),
                    ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /**
     * 编辑账号。
     *
     * <p>登录名不可改、密码不经此接口修改（改密走 {@code /password}，那要单独写审计）。
     */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PutMapping("/{id}")
    public ApiResult<AdminUserView> update(@CurrentAdminUser AdminPrincipal me,
                                           @PathVariable Integer id,
                                           @Valid @RequestBody AdminUserPayload payload,
                                           HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            AdminUserView updated = userService.updateUser(id, payload);
            auditService.success(me.username(), ACTION_MANAGE, updated.username(),
                    "修改账号资料 / 角色，当前角色 " + updated.roleIds(), ip, start);
            return ApiResult.ok(updated);
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /**
     * 启用 / 停用。
     *
     * <p>停用是**立即生效**的：后端会同时让该账号已签发的 token 失效。
     * 只改库字段等于没停 —— 那个人浏览器里的旧会话照样能用。
     */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PostMapping("/{id}/enabled")
    public ApiResult<Void> setEnabled(@CurrentAdminUser AdminPrincipal me,
                                      @PathVariable Integer id,
                                      @Valid @RequestBody EnabledPayload payload,
                                      HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        boolean enabled = Boolean.TRUE.equals(payload.enabled());
        try {
            userService.setEnabled(id, enabled, me.userId());
            auditService.success(me.username(), ACTION_MANAGE, "id=" + id,
                    enabled ? "启用账号" : "停用账号，已签发 token 已失效", ip, start);
            return ApiResult.ok();
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /** 解除登录失败锁定 */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PostMapping("/{id}/unlock")
    public ApiResult<Void> unlock(@CurrentAdminUser AdminPrincipal me,
                                  @PathVariable Integer id,
                                  HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            userService.unlock(id);
            auditService.success(me.username(), ACTION_MANAGE, "id=" + id, "解除登录失败锁定", ip, start);
            return ApiResult.ok();
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /**
     * 重置密码。
     *
     * <p>明文密码只在这一个请求里出现，**接口不回显**；审计只记「谁重置了谁」，
     * 以及是否强制下次改密 —— <b>绝不记录密码本身</b>。
     */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PostMapping("/{id}/password")
    public ApiResult<Void> resetPassword(@CurrentAdminUser AdminPrincipal me,
                                         @PathVariable Integer id,
                                         @Valid @RequestBody ResetPasswordPayload payload,
                                         HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        boolean mustChange = Boolean.TRUE.equals(payload.mustChange());
        try {
            userService.resetPassword(id, payload.password(), mustChange);
            auditService.success(me.username(), ACTION_MANAGE, "id=" + id,
                    mustChange ? "重置密码并强制下次登录修改（不记录密码）" : "重置密码（不记录密码）",
                    ip, start);
            return ApiResult.ok();
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id,
                    "重置密码失败：" + ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /** 删除账号（内置 admin 与最后一个超管会被拒绝） */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@CurrentAdminUser AdminPrincipal me,
                                  @PathVariable Integer id,
                                  HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            userService.deleteUser(id, me.userId());
            auditService.success(me.username(), ACTION_MANAGE, "id=" + id,
                    "删除管理账号（审计记录保留，审计要认人）", ip, start);
            return ApiResult.ok();
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }
}
