package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 管理端操作审计（表 {@code T_AdminAudit}）。
 *
 * <p>{@code action} 与前端 {@code src/config/permissions.ts} 的权限键同源，
 * 所以审计筛选和权限树能对上。
 *
 * <p>{@code actionLabel} 落库（而不是查询时再翻译）：历史记录的中文名不应该因为
 * 后来把某个动作改了名而漂移 —— 审计是证据，不能变。
 */
@Data
@TableName("T_AdminAudit")
public class AdminAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String operator;

    private String action;

    private String actionLabel;

    /** 归属模块（一级菜单 name，如 storage / system） */
    private String module;

    /** high / medium / low */
    private String level;

    private String target;

    /** success / failure */
    private String result;

    private String message;

    private String ip;

    private Integer costMs;

    private LocalDateTime createdAt;
}
