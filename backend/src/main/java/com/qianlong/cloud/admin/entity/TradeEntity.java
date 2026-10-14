package com.qianlong.cloud.admin.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 成交数据（表 {@code T_Trade} / {@code T_Trade_his}）。
 *
 * <p><b>现场实况（2026-10-14 实测）</b>：
 * <ul>
 *   <li>{@code T_Trade} <b>0 行</b>；{@code T_Trade_his} <b>1 行</b>（测试数据）</li>
 *   <li>⚠️ {@code TradeTime} 是 <b>{@code date}</b> 类型，**没有时分秒**
 *       （现场值 {@code 2026-06-04}）。</li>
 *   <li>⚠️ {@code Volumn} 是 <b>{@code varchar}</b>（不是数字），
 *       现场值是字符串 {@code '5'}。前端契约里也是
 *       {@code Volumn: string | null}，所以接口按字符串返回，
 *       **不擅自转成数字** —— 转了会和前端的类型不符。</li>
 * </ul>
 */
@Data
@TableName("T_Trade")
public class TradeEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 策略编号 */
    private Long StrategyID;

    /** 委托编号（与 T_Order.OrderNo 关联，是「委托 → 成交」下钻的关键） */
    private String OrderNo;

    /** 成交编号 */
    private String TradeNO;

    /** 唯一标识字段 */
    private String Account;

    private String Market;

    /** 合约 */
    private String Code;

    /** 数量。⚠️ 列类型是 varchar，按字符串返回 */
    private String Volumn;

    /** 成交价格 */
    private Double Price;

    /** 成交时间。⚠️ 列类型是 date，没有时分秒 */
    private LocalDate TradeTime;

    /** 下单账号 */
    private String UserAccount;
}
