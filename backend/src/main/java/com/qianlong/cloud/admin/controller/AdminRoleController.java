package com.qianlong.cloud.admin.controller;

import java.util.List;

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
import com.qianlong.cloud.admin.dto.SystemDtos.AdminRolePayload;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminRoleView;
import com.qianlong.cloud.admin.service.AdminRoleService;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.PermissionCatalog;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 角色与权限接口（{@code /api/system/roles}）。
 *
 * <p><b>权限点：{@code action:system-role:manage}</b>。
 * 这一页权限最大：改权限等于改所有人的可见范围，还包含「给自己提权」的风险，
 * 所以每个写操作都必须写审计（审计里带前后权限差异，便于事后追责）。
 *
 * <p>读取角色列表<b>不要求</b>该权限点：管理用户页要用角色下拉与角色名解析，
 * 若读也要求 {@code manage}，只读账号（如 viewer）根本渲染不出角色名，
 * 只能显示「角色 3」这种没有信息量的内容。
 */
@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
public class AdminRoleController {

    private static final String ACTION_MANAGE = "action:system-role:manage";

    private final AdminRoleService roleService;
    private final AuditService auditService;
    private final PermissionCatalog catalog;

    /** 全部角色（含权限点与引用人数） */
    @SaCheckLogin
    @GetMapping
    public ApiResult<List<AdminRoleView>> list() {
        return ApiResult.ok(roleService.listRoles());
    }

    @SaCheckLogin
    @GetMapping("/{id}")
    public ApiResult<AdminRoleView> detail(@PathVariable Integer id) {
        return ApiResult.ok(roleService.getRole(id));
    }

    /**
     * 全部合法权限键（供角色页做「显示即授权」的兜底与排查用）。
     *
     * <p>前端权限树由菜单树推导，正常情况下不需要这个接口；
     * 但如果两边清单不一致（有人只改了前端菜单、没同步后端字典），
     * 有了它就能立刻看出来，而不是等到「某个权限点永远勾不上」才发现。
     */
    @SaCheckLogin
    @GetMapping("/permission-keys")
    public ApiResult<List<String>> permissionKeys() {
        return ApiResult.ok(catalog.allPermissionKeys().stream().sorted().toList());
    }

    /** 新建角色 */
    @SaCheckPermission(ACTION_MANAGE)
    @PostMapping
    public ApiResult<AdminRoleView> create(@CurrentAdminUser AdminPrincipal me,
                                           @Valid @RequestBody AdminRolePayload payload,
                                           HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            AdminRoleView created = roleService.createRole(payload);
            auditService.success(me.username(), ACTION_MANAGE, created.code(),
                    "新建角色，权限点 " + created.permissions().size() + " 个", ip, start);
            return ApiResult.ok(created);
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, payload.code(), ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /**
     * 编辑角色（含权限）。
     *
     * <p>审计里记的是<b>变更后的权限点数量与差异摘要</b>，不是完整权限数组 ——
     * 完整数组可能很长，而审计表要能一眼看出「这次改了什么」。
     */
    @SaCheckPermission(ACTION_MANAGE)
    @PutMapping("/{id}")
    public ApiResult<AdminRoleView> update(@CurrentAdminUser AdminPrincipal me,
                                           @PathVariable Integer id,
                                           @Valid @RequestBody AdminRolePayload payload,
                                           HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            AdminRoleView before = roleService.getRole(id);
            AdminRoleView after = roleService.updateRole(id, payload);
            auditService.success(me.username(), ACTION_MANAGE, after.code(),
                    diff(before, after), ip, start);
            return ApiResult.ok(after);
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /** 删除角色（内置角色、以及仍被账号引用的角色会被拒绝） */
    @SaCheckPermission(ACTION_MANAGE)
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@CurrentAdminUser AdminPrincipal me,
                                  @PathVariable Integer id,
                                  HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            AdminRoleView before = roleService.getRole(id);
            roleService.deleteRole(id);
            auditService.success(me.username(), ACTION_MANAGE, before.code(),
                    "删除角色「" + before.name() + "」，原有权限点 " + before.permissions().size() + " 个",
                    ip, start);
            return ApiResult.ok();
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "id=" + id, ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /** 生成「这次改了什么」的摘要（新增/移除的权限点，超管用 * 表示） */
    private static String diff(AdminRoleView before, AdminRoleView after) {
        List<String> added = after.permissions().stream()
                .filter(k -> !before.permissions().contains(k)).sorted().toList();
        List<String> removed = before.permissions().stream()
                .filter(k -> !after.permissions().contains(k)).sorted().toList();
        StringBuilder sb = new StringBuilder();
        sb.append("权限点 ").append(before.permissions().size())
                .append(" → ").append(after.permissions().size());
        if (!added.isEmpty()) {
            sb.append("；新增 ").append(added);
        }
        if (!removed.isEmpty()) {
            sb.append("；移除 ").append(removed);
        }
        if (added.isEmpty() && removed.isEmpty()) {
            sb.append("；权限点未变");
        }
        return sb.toString();
    }
}
