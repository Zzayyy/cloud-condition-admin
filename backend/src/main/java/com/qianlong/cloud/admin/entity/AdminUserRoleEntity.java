package com.qianlong.cloud.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 账号-角色关系（表 {@code T_AdminUserRole}）。 */
@Data
@TableName("T_AdminUserRole")
public class AdminUserRoleEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private Integer roleId;
}
