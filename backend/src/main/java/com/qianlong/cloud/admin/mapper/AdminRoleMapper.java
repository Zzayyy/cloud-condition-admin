package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.AdminRoleEntity;

/** 后台角色 Mapper。 */
public interface AdminRoleMapper extends BaseMapper<AdminRoleEntity> {

    /**
     * 统计每个角色被多少管理账号引用。
     *
     * <p>用一次 GROUP BY 而不是「每个角色查一次 COUNT」：角色数量会增长，
     * 循环查库是 N+1，且前端列表页每次刷新都会调用本接口。
     *
     * <p>列名显式起别名为 {@code roleId}/{@code userCount}，不依赖驼峰-下划线映射配置
     * （本模块的列名本身就是驼峰，映射规则一旦被改动就会静默取到 null）。
     */
    @Select("""
            SELECT roleId AS roleId, COUNT(DISTINCT userId) AS userCount
              FROM T_AdminUserRole
             GROUP BY roleId
            """)
    List<RoleUserCountRow> countUsersByRole();

    /** 单个角色的引用人数 */
    @Select("SELECT COUNT(DISTINCT userId) FROM T_AdminUserRole WHERE roleId = #{roleId}")
    long countUsersOfRole(Integer roleId);

    /**
     * 持有通配符权限（超管）的账号数。
     *
     * <p>用于「不能删掉最后一个超管」这道护栏 —— 现场没有别的恢复手段，
     * 一旦超管角色/账号被清空，后台就再也进不去了。
     */
    @Select("""
            SELECT COUNT(DISTINCT ur.userId)
              FROM T_AdminUserRole ur
              JOIN T_AdminRolePermission rp ON rp.roleId = ur.roleId
              JOIN T_AdminUser u ON u.id = ur.userId
             WHERE rp.permissionKey = '*'
               AND u.enabled = 1
            """)
    long countEnabledWildcardUsers();

    /** 引用了指定角色、且账号启用的数量（用于「删角色会影响几个人」） */
    @Select("""
            SELECT COUNT(DISTINCT ur.userId)
              FROM T_AdminUserRole ur
              JOIN T_AdminUser u ON u.id = ur.userId
             WHERE ur.roleId = #{roleId} AND u.enabled = 1
            """)
    long countEnabledUsersOfRole(Integer roleId);

    /** 角色引用人数行 */
    record RoleUserCountRow(Integer roleId, Long userCount) {
    }
}
