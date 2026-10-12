package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 密码策略与登录失败锁定策略（单行配置表，id 恒为 1）。 */
@Data
@TableName("T_AdminPasswordPolicy")
public class AdminPasswordPolicyEntity {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private Integer minLength;

    private Boolean requireUpper;

    private Boolean requireLower;

    private Boolean requireDigit;

    private Boolean requireSymbol;

    /** 连续失败达到该次数即锁定，0 = 不锁定 */
    private Integer maxFailedAttempts;

    private Integer lockMinutes;

    /** 密码有效期（天），0 = 永不过期 */
    private Integer expireDays;

    private Boolean forceChangeOnFirstLogin;

    /** 禁止与最近 N 次密码重复，0 = 不校验历史 */
    private Integer historySize;

    private LocalDateTime updatedAt;
}
