package com.qianlong.cloud.admin.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 委托下单（表 {@code T_Order} / {@code T_Order_his}）。
 *
 * <p>两张表结构一致，共用一个实体，靠 {@code TradeTable} 切换表名。
 *
 * <p><b>现场实况（2026-10-14 实测）</b>：
 * <ul>
 *   <li>{@code T_Order} <b>0 行</b>；{@code T_Order_his} <b>1 行</b>（测试数据）</li>
 *   <li>两表都**只有主键索引**（{@code T_Order_his} 甚至连主键索引都没有，
 *       {@code ID} 是 {@code bigint DEFAULT 0}）。本项目不改动既有表结构，
 *       所以不加索引，查询策略上靠默认的「主键/ID 倒序分页」。</li>
 *   <li>⚠️ {@code OrderTime} 是 <b>{@code date}</b> 类型（不是 datetime），
 *       现场值形如 {@code 2026-06-04} —— <b>没有时分秒</b>。
 *       而列注释写的是「委托下单时间」，容易让人以为是精确时刻。
 *       接口按 {@code yyyy-MM-dd} 返回（见 OrderService 的说明）。</li>
 * </ul>
 *
 * <p>列名照既有表原样保留（{@code ID} / {@code BSType} 等），
 * 与 T_Admin* 那批小驼峰新表风格不同 —— **以现场为准**。
 */
@Data
@TableName("T_Order")
public class OrderEntity {

    /**
     * 委托单主键。
     *
     * <p>⚠️ 主表是 {@code AUTO_INCREMENT}，但**历史表不是**
     * （{@code T_Order_his.ID} 是 {@code bigint NOT NULL DEFAULT 0}）。
     * 用 {@link IdType#INPUT} 表示「由调用方/数据库给值」，
     * 这里只是读，不涉及插入，所以不影响。
     */
    @TableId(type = IdType.INPUT)
    private Long ID;

    /** 策略编号 */
    private Long StrategyID;

    /** 客户下单账号 */
    private String UserAccount;

    /** 唯一标识字段 */
    private String Account;

    /** 合约代码 */
    private String Code;

    /** 市场 */
    private String Market;

    /** 委托价格 */
    private Double OrderPrice;

    /** 委托数量 */
    private Double OrderVolumn;

    /** 买卖方向 */
    private Integer BSType;

    /** 开仓平仓类型 */
    private Integer OCType;

    /**
     * 委托下单时间。
     *
     * <p>⚠️ 列类型是 {@code date}（不是 datetime），所以**没有时分秒**。
     */
    private LocalDate OrderTime;

    /** 下单参数（非统一参数以 JSON 存放） */
    private String OrderParam;

    /**
     * 委托状态。
     *
     * <p>⚠️ 取值范围**未知**：协议文档没定义，现场 {@code T_Order_his} 只有 1 条
     * 测试数据（值为 1）。页面只展示 {@code StatueDis} 文本与原值，
     * 不做状态着色/统计 —— 这是 README 里记着的待确认项。
     * 后端同样不臆造映射。
     */
    private Integer OrderStatue;

    /** 后台返回的委托编号 */
    private String OrderNo;

    /** 合约名称 */
    private String CodeName;

    /** 委托状态描述（页面展示这个文本，而不是猜 OrderStatue 的含义） */
    private String StatueDis;

    private String Reason;
}
