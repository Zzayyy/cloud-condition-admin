package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminRolePayload;
import com.qianlong.cloud.admin.dto.SystemDtos.AdminRoleView;
import com.qianlong.cloud.admin.entity.AdminRoleEntity;
import com.qianlong.cloud.admin.entity.AdminRolePermissionEntity;
import com.qianlong.cloud.admin.entity.AdminUserRoleEntity;
import com.qianlong.cloud.admin.mapper.AdminRoleMapper;
import com.qianlong.cloud.admin.mapper.AdminRolePermissionMapper;
import com.qianlong.cloud.admin.mapper.AdminUserRoleMapper;

import lombok.RequiredArgsConstructor;

/**
 * 角色与权限。
 *
 * <p><b>这一页权限最大</b>：改权限等于改所有人的可见范围，还包含「给自己提权」的风险，
 * 所以所有写操作都要求调用方写审计（见 Controller）。
 *
 * <p>三条护栏：
 * <ol>
 *   <li><b>内置角色不可删除、code 与名称不可修改</b>
 *       —— 删掉超管角色会让后台再也进不去，现场没有别的恢复手段；</li>
 *   <li><b>有账号在用的角色不给删</b>（或至少要说清影响），避免静默剥夺权限；</li>
 *   <li><b>不能把最后一个超管角色的通配符权限摘掉</b>，否则同样再也进不去。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class AdminRoleService {

    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final PermissionCatalog catalog;

    /**
     * 全部角色（含权限点与引用人数）。
     *
     * <p>前端 {@code listRoles()} 不分页 —— 角色数量天然很少（个位数到几十），
     * 分页反而让「角色下拉框」要多一次请求。
     */
    public List<AdminRoleView> listRoles() {
        List<AdminRoleEntity> roles = roleMapper.selectList(
                Wrappers.<AdminRoleEntity>lambdaQuery().orderByAsc(AdminRoleEntity::getId));
        if (roles.isEmpty()) {
            return List.of();
        }

        // 一次性把「角色 → 权限点」与「角色 → 引用人数」取回来，避免 N+1
        Map<Integer, List<String>> permissionMap = new HashMap<>();
        for (AdminRolePermissionEntity row : rolePermissionMapper.selectList(null)) {
            permissionMap.computeIfAbsent(row.getRoleId(), k -> new ArrayList<>()).add(row.getPermissionKey());
        }
        Map<Integer, Long> userCountMap = new HashMap<>();
        for (AdminRoleMapper.RoleUserCountRow row : roleMapper.countUsersByRole()) {
            userCountMap.put(row.roleId(), row.userCount() == null ? 0L : row.userCount());
        }

        List<AdminRoleView> out = new ArrayList<>(roles.size());
        for (AdminRoleEntity role : roles) {
            List<String> permissions = permissionMap.getOrDefault(role.getId(), List.of());
            out.add(new AdminRoleView(
                    role.getId(),
                    role.getCode(),
                    role.getName(),
                    role.getDescription(),
                    Boolean.TRUE.equals(role.getBuiltin()),
                    // 通配符角色只回 `*` 一项，不展开 —— 与前端 isWildcardRole 的判定一致
                    List.copyOf(permissions),
                    userCountMap.getOrDefault(role.getId(), 0L),
                    DateTimes.format(role.getCreatedAt()),
                    DateTimes.format(role.getUpdatedAt())));
        }
        return out;
    }

    public AdminRoleView getRole(Integer id) {
        AdminRoleEntity role = requireRole(id);
        return listRoles().stream()
                .filter(v -> v.id().equals(role.getId()))
                .findFirst()
                .orElseThrow(() -> BizException.notFound("角色不存在（id=" + id + "）"));
    }

    /** 新建角色 */
    @Transactional(rollbackFor = Exception.class)
    public AdminRoleView createRole(AdminRolePayload payload) {
        String code = payload.code().trim();
        if (roleMapper.selectCount(Wrappers.<AdminRoleEntity>lambdaQuery()
                .eq(AdminRoleEntity::getCode, code)) > 0) {
            throw BizException.conflict("角色编码 " + code + " 已存在，请换一个");
        }
        // 权限键必须逐字合法：不静默丢弃未知键（丢弃会让管理员以为授了权）
        List<String> permissions = catalog.normalize(payload.permissions());

        AdminRoleEntity role = new AdminRoleEntity();
        role.setCode(code);
        role.setName(payload.name().trim());
        role.setDescription(StringUtils.hasText(payload.desc()) ? payload.desc().trim() : null);
        // 新建的一律不是内置：内置只有初始化脚本能造出来，接口不能把自己的角色标成「不可删」
        role.setBuiltin(false);
        LocalDateTime now = LocalDateTime.now();
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleMapper.insert(role);

        replacePermissions(role.getId(), permissions);
        return getRole(role.getId());
    }

    /**
     * 编辑角色。
     *
     * <p>内置角色的 {@code code} 与 {@code name} 不可改（审计靠 code 认人，改名会让历史对不上）；
     * 权限仍可调整，但不能把最后一个超管角色的 {@code *} 摘掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminRoleView updateRole(Integer id, AdminRolePayload payload) {
        AdminRoleEntity role = requireRole(id);
        boolean builtin = Boolean.TRUE.equals(role.getBuiltin());

        if (builtin) {
            if (!role.getCode().equals(payload.code().trim())) {
                throw BizException.conflict("内置角色的 code 不可修改（审计靠 code 认人）");
            }
            if (!role.getName().equals(payload.name().trim())) {
                throw BizException.conflict("内置角色的名称不可修改");
            }
        }

        List<String> permissions = catalog.normalize(payload.permissions());
        boolean hadWildcard = rolePermissionMapper.selectPermissionKeysByRoleId(id)
                .contains(PermissionCatalog.WILDCARD);
        boolean willHaveWildcard = permissions.contains(PermissionCatalog.WILDCARD);

        if (hadWildcard && !willHaveWildcard) {
            /*
             * 摘掉通配符 = 把一批超管降权。若这是最后一个超管角色，后台就再也进不去了。
             * 注意这里判的是「还有没有别的启用超管账号」而不是「还有没有别的通配符角色」——
             * 真正决定「能不能进后台」的是账号，不是角色。
             */
            long remaining = roleMapper.countEnabledWildcardUsers();
            long affected = roleMapper.countEnabledUsersOfRole(id);
            if (remaining - affected <= 0) {
                throw BizException.conflict(
                        "该角色是当前唯一的超级管理员来源（涉及 " + affected
                                + " 个启用账号），不允许移除通配符权限 *：否则后台将无法进入");
            }
        }

        role.setName(payload.name().trim());
        role.setDescription(StringUtils.hasText(payload.desc()) ? payload.desc().trim() : null);
        if (!builtin) {
            role.setCode(payload.code().trim());
        }
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);

        replacePermissions(id, permissions);
        return getRole(id);
    }

    /**
     * 删除角色。
     *
     * <p>有账号在用时不删 —— 删除会让那些账号**静默**失去权限（他们下次刷新才发现）。
     * 前端也提示了这一点，但后端必须真的拦住（前端只是提示）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Integer id) {
        AdminRoleEntity role = requireRole(id);
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw BizException.conflict(
                    "内置角色「" + role.getName() + "」不可删除：删掉超管角色会让后台再也进不去");
        }
        long userCount = roleMapper.countUsersOfRole(id);
        if (userCount > 0) {
            throw BizException.conflict(
                    "角色「" + role.getName() + "」仍有 " + userCount
                            + " 个管理账号在用，请先解除这些账号与该角色的关联再删除");
        }
        rolePermissionMapper.delete(Wrappers.<AdminRolePermissionEntity>lambdaQuery()
                .eq(AdminRolePermissionEntity::getRoleId, id));
        userRoleMapper.delete(Wrappers.<AdminUserRoleEntity>lambdaQuery()
                .eq(AdminUserRoleEntity::getRoleId, id));
        roleMapper.deleteById(id);
    }

    public long countUsersOfRole(Integer roleId) {
        return roleMapper.countUsersOfRole(roleId);
    }

    private AdminRoleEntity requireRole(Integer id) {
        AdminRoleEntity role = id == null ? null : roleMapper.selectById(id);
        if (role == null) {
            throw BizException.notFound("角色不存在（id=" + id + "）");
        }
        return role;
    }

    private void replacePermissions(Integer roleId, List<String> permissions) {
        rolePermissionMapper.delete(Wrappers.<AdminRolePermissionEntity>lambdaQuery()
                .eq(AdminRolePermissionEntity::getRoleId, roleId));
        for (String key : permissions) {
            AdminRolePermissionEntity entity = new AdminRolePermissionEntity();
            entity.setRoleId(roleId);
            entity.setPermissionKey(key);
            rolePermissionMapper.insert(entity);
        }
    }
}
