package com.qianlong.cloud.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 角色持有的权限键（表 {@code T_AdminRolePermission}）。 */
@Data
@TableName("T_AdminRolePermission")
public class AdminRolePermissionEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer roleId;

    /** 权限键：{@code menu:<菜单name>} / {@code action:<模块>:<动作>} / {@code *} */
    private String permissionKey;
}
