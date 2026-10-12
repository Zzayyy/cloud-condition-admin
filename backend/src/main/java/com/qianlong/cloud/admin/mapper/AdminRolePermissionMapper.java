package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.AdminRolePermissionEntity;

/** 角色权限点 Mapper。 */
public interface AdminRolePermissionMapper extends BaseMapper<AdminRolePermissionEntity> {

    /**
     * 取一个账号通过角色拿到的全部权限键（去重）。
     *
     * <p>每次权限校验都会走这条 SQL —— 这是刻意的：改权限<b>立即生效</b>，
     * 不需要等 token 过期。代价是一次带索引的 JOIN，对后台管理系统完全可接受；
     * 相比之下「改了权限但旧 token 还能用」是真实的越权风险。
     */
    @Select("""
            SELECT DISTINCT rp.permissionKey
              FROM T_AdminUserRole ur
              JOIN T_AdminRolePermission rp ON rp.roleId = ur.roleId
             WHERE ur.userId = #{userId}
            """)
    List<String> selectPermissionKeysByUserId(@Param("userId") Integer userId);

    /** 取一个账号的角色 code 集合 */
    @Select("""
            SELECT r.code
              FROM T_AdminUserRole ur
              JOIN T_AdminRole r ON r.id = ur.roleId
             WHERE ur.userId = #{userId}
             ORDER BY r.id
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Integer userId);

    /** 取指定角色的全部权限键 */
    @Select("SELECT permissionKey FROM T_AdminRolePermission WHERE roleId = #{roleId}")
    List<String> selectPermissionKeysByRoleId(@Param("roleId") Integer roleId);
}
