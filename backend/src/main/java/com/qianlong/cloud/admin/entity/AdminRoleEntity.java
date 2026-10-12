package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 后台角色（表 {@code T_AdminRole}）。 */
@Data
@TableName("T_AdminRole")
public class AdminRoleEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 角色编码，唯一；创建后不可修改（审计靠它认人） */
    private String code;

    private String name;

    /** 说明。列名是 {@code description}，前端字段叫 {@code desc}（避免与 SQL 关键字冲突） */
    private String description;

    /** 是否内置：内置角色不可删除、code 与名称不可修改 */
    private Boolean builtin;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
