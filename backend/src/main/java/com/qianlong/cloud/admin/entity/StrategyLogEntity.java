package com.qianlong.cloud.admin.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 策略单日志（表 {@code T_StrategyLog}，现场 17430 行）。
 *
 * <h2>⚠️ 现场实况（2026-10-14 实测）：三列全 NULL，必须如实呈现</h2>
 * <pre>
 *   Strategy     非空 17430/17430   bigint
 *   Status       NULL 17430/17430   ← 整列为空！
 *   Info         非空 17430/17430   text，如 "new trade inserted,tradeno = 1234567"
 *   OccurTIme    非空 17430/17430   date（不是 datetime！）
 *   FromIP       NULL 17430/17430   ← 整列为空！
 *   FromMac      NULL 17430/17430   ← 整列为空！
 *   FromAccount  非空 17430/17430
 * </pre>
 *
 * <p><b>1. {@code Status} 整列为 NULL。</b>前端页面上的状态下拉
 * （触发成功 / 触发失败 / 已暂停 / …）来自 {@code mock/strategy.ts} 的
 * {@code LOG_STATUS}，那是**演示数据编出来的**，现实里一个都不存在。
 * 所以后端提供 {@code /strategy/logs/statuses} 返回**真实的**去重取值
 * （现在是空数组），让前端不要再从 mock 取选项。
 *
 * <p><b>2. {@code FromIP} / {@code FromMac} 整列为 NULL。</b>
 * 与 {@code T_UserLog} 的设备三列是同一类情况：不是查询写错了，
 * 是真的没有数据。所以「按来源 IP 筛」必然查出 0 条。
 *
 * <p><b>3. {@code OccurTIme} 是 {@code date} 而非 {@code datetime}</b>，
 * 只有日期没有时分秒。接口按 {@code yyyy-MM-dd} 返回，不补 {@code 00:00:00}。
 *
 * <p>⚠️ <b>还有一个坑：该列存了非法的空字符串</b>，
 * 直接写 {@code WHERE OccurTIme <> ''} 会报
 * <b>MySQL 1525 (Incorrect DATE value: '')</b>。
 * 所以本模块对日期列**只用 {@code IS NULL} / 范围比较**，
 * 绝不用 {@code <> ''} 或 {@code != ''}（见 StrategyLogMapper 的注释）。
 *
 * <p>列名照既有表原样保留（注意 {@code OccurTIme} 里那个大写的 {@code I}，
 * 是现场列名就这么写的，不是笔误）。
 */
@Data
@TableName("T_StrategyLog")
public class StrategyLogEntity {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 条件单/策略编号 */
    private Long Strategy;

    /** 状态。⚠️ 现场整列为 NULL */
    private String Status;

    /** 日志主要信息 */
    private String Info;

    /** 发生时间。⚠️ {@code date} 类型，没有时分秒；且存过非法的 '' */
    private LocalDate OccurTIme;

    /** 触发的设备 IP。⚠️ 现场整列为 NULL */
    private String FromIP;

    /** 触发的设备 MAC。⚠️ 现场整列为 NULL */
    private String FromMac;

    /** 触发的账号（客户端触发有效，或管理员） */
    private String FromAccount;
}
