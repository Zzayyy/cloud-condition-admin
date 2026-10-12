package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 后台管理账号（表 {@code T_AdminUser}）。
 *
 * <p><b>与云单业务用户 {@code T_User} 完全无关</b>：这是「谁能登录后台」，
 * {@code T_User} 是客户的钱包账号。
 *
 * <p>{@code passwordHash} 只写不读 —— 出参 DTO 里根本没有这个字段，
 * 从类型上杜绝「接口不小心回显哈希」。
 */
@Data
@TableName("T_AdminUser")
public class AdminUserEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String username;

    private String displayName;

    /** BCrypt 哈希。**不放进任何出参 DTO** */
    private String passwordHash;

    private Boolean enabled;

    private Boolean locked;

    private Integer failedAttempts;

    private Boolean mustChangePassword;

    /** 被锁定的时间，用于按 {@code lockMinutes} 到期自动解锁 */
    private LocalDateTime lockTime;

    /** 最后一次改密时间，用于按 {@code expireDays} 判定是否过期 */
    private LocalDateTime passwordUpdateTime;

    private LocalDateTime lastLoginTime;

    private String lastLoginIP;

    private Integer loginCount;

    /** 内置账号（admin）：不可删除 */
    private Boolean builtin;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
