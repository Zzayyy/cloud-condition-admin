package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.AdminUserRoleEntity;

/** 账号-角色关系 Mapper。 */
public interface AdminUserRoleMapper extends BaseMapper<AdminUserRoleEntity> {

    /** 取某个账号的角色 id 集合 */
    @Select("SELECT roleId FROM T_AdminUserRole WHERE userId = #{userId} ORDER BY roleId")
    List<Integer> selectRoleIdsByUserId(@Param("userId") Integer userId);

    /**
     * 批量取多个账号的角色 id（列表页用，避免 N+1）。
     *
     * <p>列名显式别名，不依赖驼峰映射配置。
     */
    @Select("""
            <script>
            SELECT userId AS userId, roleId AS roleId
              FROM T_AdminUserRole
             WHERE userId IN
             <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY userId, roleId
            </script>
            """)
    List<UserRoleRow> selectRoleIdsByUserIds(@Param("userIds") List<Integer> userIds);

    /** 账号-角色行 */
    record UserRoleRow(Integer userId, Integer roleId) {
    }
}
