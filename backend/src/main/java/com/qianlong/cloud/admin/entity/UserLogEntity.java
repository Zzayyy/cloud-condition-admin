package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 云单用户登录/操作日志（表 {@code T_UserLog}）。
 *
 * <p>⚠️ 与 {@code T_AdminLoginLog}（后台管理端登录日志）不是一张表，
 * 不要混：这里是客户账号的轨迹，19.1 万行。
 *
 * <p><b>现场实测的两个坑（页面已如实说明，后端不要「修正」）</b>：
 * <ol>
 *   <li>{@code LogIP} / {@code LogMAC} / {@code LogOS} 三列**全部为 NULL**
 *       （19.1 万行无例外）。所以「按设备查」只会查出空结果 ——
 *       这不是查询写错了，是真的没有数据。</li>
 *   <li>{@code Info} 实际取值只有 {@code user registed} 与
 *       {@code user insert or updated} 两类（见现场数据），
 *       并不承载「失败原因」这类语义。</li>
 * </ol>
 *
 * <p>表上**只有主键索引**（实测 {@code SHOW INDEX} 只有 PRIMARY）。
 * 本模块不改动这张表（不允许加索引），所以查询策略上：
 * 优先用主键倒序分页，关键字/时间过滤交给 WHERE 但要注意代价
 * （19 万行下 LIKE 约 100~150ms，可接受；详见 AccountLogService 的说明）。
 */
@Data
@TableName("T_UserLog")
public class UserLogEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String Account;

    private LocalDateTime LogTime;

    private String LogIP;

    private String LogMAC;

    private String LogOS;

    /** 日志基本信息，不能包含所有信息，做相关的提示 */
    private String Info;
}
