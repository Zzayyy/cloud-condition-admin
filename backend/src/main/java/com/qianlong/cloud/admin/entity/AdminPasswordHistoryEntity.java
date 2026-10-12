package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 历史密码哈希（表 {@code T_AdminPasswordHistory}），支撑策略里的 {@code historySize}。 */
@Data
@TableName("T_AdminPasswordHistory")
public class AdminPasswordHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;

    private String passwordHash;

    private LocalDateTime createdAt;
}
