package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 管理端登录日志（表 {@code T_AdminLoginLog}）。
 *
 * <p>⚠️ 与云单客户的 {@code T_UserLog} 不是一张表 —— 那是 19 万行的客户登录日志。
 */
@Data
@TableName("T_AdminLoginLog")
public class AdminLoginLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名。失败时也记录，可能是并不存在的账号 */
    private String username;

    private LocalDateTime loginTime;

    private String loginIP;

    /** 客户端 / 操作系统（来自 User-Agent） */
    private String loginOS;

    private Boolean success;

    private String info;
}
