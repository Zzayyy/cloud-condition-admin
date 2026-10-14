package com.qianlong.cloud.admin.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 云条件单（表 {@code T_Conditional} / 历史表 {@code T_Conditional_his}）。
 *
 * <p>两张表结构一致（历史表多一个 {@code Name} 的唯一索引），
 * 所以共用一个实体，靠 {@code tableName} 动态切换 —— 见 {@code ConditionalMapper}。
 *
 * <p><b>现场实况（2026-10-14 实测）：两张表都是 0 行。</b>
 * 本模块<b>只读这两张表 + 视图</b>，页面会如实显示为空 ——
 * 目的是让「表里没有数据」这件事本身被看见（这是个已知待确认项），
 * 而不是拿 Redis {@code strategy-*} 里的数据把空表填满、掩盖掉它。
 * 详见 {@code ConditionalService} 的类注释。
 *
 * <p>列名照既有表原样保留（首字母大写），与 T_Admin* 那批小驼峰新表风格不同 ——
 * **以现场为准**，不改动既有表。
 *
 * <p>⚠️ 主键 {@code id} 是 {@code bigint} **且不是自增**（现场 DDL 没有 AUTO_INCREMENT）：
 * id 由业务侧生成（Redis 里的样例是 {@code 20260810000001} 这种日期+序号）。
 * 所以插入时不能用数据库自增，必须显式给值。
 */
@Data
@TableName("T_Conditional")
public class ConditionalEntity {

    /** 条件单编号。bigint 但非自增，由业务侧生成 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 拼接的唯一账号信息，如 300130000461_1_6 */
    private String Account;

    /** 用户账号 */
    private String UserAccount;

    /** 条件单原始内容（JSON 字符串） */
    private String Strategy;

    /** 加入数据库的时间 */
    private LocalDateTime AddTime;

    /** 当日运行时间 */
    private LocalDateTime RunTime;

    /** 运行后结束时间 */
    private LocalDateTime CloseTime;

    /** 运行次数 */
    private Integer RunCount;

    /** 从什么时间开始生效 */
    private LocalDateTime BeginTime;

    /** 什么时间结束，不再执行 */
    private LocalDateTime EndTime;

    /** 触发状态：0 未触发，1 已触发 */
    private Integer Status;

    /** 有效标志：0 有效，1 暂停，2 删除 */
    private Integer Flag;

    /** 简单描述 */
    private String Description;

    /** 有效期，0 为长期有效 */
    private Integer Validity;

    private LocalDateTime ModifyTime;

    private LocalDateTime TriggeredTime;

    /** 修改原因等 */
    private String Reason;

    /** 云单状态：0 停止，1 运行，2 失效，3 结束 */
    private Integer Mode;

    /**
     * 云条件单类型：0 保留，1 开仓，2 平仓，3 按合约止盈止损，4 按标的止盈止损。
     *
     * <p>⚠️ {@code T_Conditional_his.Type} 列**没有注释**（现场 DDL 里是裸的
     * {@code int DEFAULT NULL}），而主表的注释列了上述映射。
     * 两边用同一套解释 —— 它们是同一份数据的两个落点，类型语义不可能不同。
     */
    private Integer Type;

    /** 条件单名称 */
    private String Name;
}
