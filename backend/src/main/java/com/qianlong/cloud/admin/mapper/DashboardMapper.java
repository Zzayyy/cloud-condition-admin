package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 总览页的聚合查询。
 *
 * <p>总览要的数字横跨多张表，而且**必须算主表 + 历史表**：
 * 委托可能还在 {@code T_Order}、成交已归档到 {@code T_Trade_his}（或反之），
 * 只看一张表会得出偏小的数字。这一点与 {@code TradeMapper.selectByOrderNo}
 * 的处理口径一致。
 *
 * <p><b>全部只读</b>，没有任何写方法。
 *
 * <h2>⚠️ 三个必须照现场处理的点</h2>
 * <ol>
 *   <li><b>{@code T_Trade.Volumn} 是 {@code varchar}</b>（现场值是 {@code '5'}）。
 *       直接 {@code SUM} 会做隐式转换、遇非数字时变成 0 并**带警告**，
 *       所以这里用 {@code REGEXP} 先判断是不是数字 —— 非数字按 0 计而不是报错。</li>
 *   <li><b>日期列有 {@code date} 类型</b>（{@code OrderTime} / {@code TradeTime}
 *       没有时分秒），所以按天聚合一律走 {@code DATE(...)}，
 *       不要用字符串前缀比较 —— 那在跨类型时会静默筛错。</li>
 *   <li><b>「已产生成交的委托」要跨表关联 {OrderNo}</b>：
 *       委托在两张表、成交也在两张表，只查主表会漏掉已归档的关联。</li>
 * </ol>
 */
public interface DashboardMapper {

    /* ============================== 条件单 ============================== */

    /**
     * 条件单汇总（运行中 / 已触发）。
     *
     * <p>口径是 {@code V_Conditional}（主表 + 历史表的并集视图），
     * 与条件单页的「全部」数据源一致 —— 只统计主表会漏掉已归档的条件单。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN Mode = 1 THEN 1 ELSE 0 END), 0) AS running,
                   COALESCE(SUM(CASE WHEN Status = 1 THEN 1 ELSE 0 END), 0) AS triggered
              FROM V_Conditional
            """)
    CountRow selectConditionalStats();

    /* ============================== 委托单 ============================== */

    /** 委托单总数（主表 + 历史表） */
    @Select("""
            SELECT (SELECT COUNT(*) FROM T_Order) + (SELECT COUNT(*) FROM T_Order_his)
            """)
    long selectOrderTotal();

    /**
     * **已产生成交**的委托数（按 {@code OrderNo} 跨四张表关联）。
     *
     * <p>为什么不是「按 OrderStatue 判断已成交」：那个字段的取值枚举协议里没定义
     * （现场值 8 的含义无从确认），而 {@code OrderNo} 出现在成交表里是**客观事实**。
     * 用可验证的关联关系，而不是猜状态码。
     */
    @Select("""
            SELECT COUNT(*) FROM (
              SELECT o.OrderNo
                FROM T_Order o
               WHERE o.OrderNo IS NOT NULL AND o.OrderNo <> ''
                 AND o.OrderNo IN (SELECT OrderNo FROM T_Trade
                                   UNION SELECT OrderNo FROM T_Trade_his)
              UNION ALL
              SELECT h.OrderNo
                FROM T_Order_his h
               WHERE h.OrderNo IS NOT NULL AND h.OrderNo <> ''
                 AND h.OrderNo IN (SELECT OrderNo FROM T_Trade
                                   UNION SELECT OrderNo FROM T_Trade_his)
            ) matched
            """)
    long selectOrderFilled();

    /* ============================== 成交回报 ============================== */

    /** 成交笔数（主表 + 历史表） */
    @Select("""
            SELECT (SELECT COUNT(*) FROM T_Trade) + (SELECT COUNT(*) FROM T_Trade_his)
            """)
    long selectTradeTotal();

    /**
     * 成交量合计（主表 + 历史表）。
     *
     * <p>⚠️ {@code Volumn} 是 {@code varchar}，所以先用 {@code REGEXP} 排除非数字，
     * 再 {@code CAST}。不这么写的话，一条脏数据（如 {@code 'abc'}）会被隐式转成 0
     * 并且只在 MySQL 警告里体现 —— 订单量统计会**静默偏小**。
     */
    @Select("""
            SELECT COALESCE(SUM(v), 0) FROM (
              SELECT CASE WHEN Volumn REGEXP '^[0-9]+(\\\\.[0-9]+)?$'
                          THEN CAST(Volumn AS DECIMAL(20,4)) ELSE 0 END AS v
                FROM T_Trade
              UNION ALL
              SELECT CASE WHEN Volumn REGEXP '^[0-9]+(\\\\.[0-9]+)?$'
                          THEN CAST(Volumn AS DECIMAL(20,4)) ELSE 0 END AS v
                FROM T_Trade_his
            ) allvol
            """)
    java.math.BigDecimal selectTradeVolume();

    /* ============================== 按天趋势 ============================== */

    /** 条件单按天新增（并集视图；{@code AddTime} 可能为 NULL，排除掉） */
    @Select("""
            SELECT DATE_FORMAT(AddTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
              FROM V_Conditional
             WHERE AddTime IS NOT NULL
               AND DATE(AddTime) BETWEEN #{from} AND #{to}
             GROUP BY day
             ORDER BY day
            """)
    List<DayCount> selectConditionalDaily(@Param("from") String from, @Param("to") String to);

    /** 委托单按天（主表 + 历史表） */
    @Select("""
            SELECT day, SUM(cnt) AS cnt FROM (
              SELECT DATE_FORMAT(OrderTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
                FROM T_Order
               WHERE OrderTime IS NOT NULL AND DATE(OrderTime) BETWEEN #{from} AND #{to}
               GROUP BY day
              UNION ALL
              SELECT DATE_FORMAT(OrderTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
                FROM T_Order_his
               WHERE OrderTime IS NOT NULL AND DATE(OrderTime) BETWEEN #{from} AND #{to}
               GROUP BY day
            ) u
             GROUP BY day
             ORDER BY day
            """)
    List<DayCount> selectOrderDaily(@Param("from") String from, @Param("to") String to);

    /** 成交按天（主表 + 历史表） */
    @Select("""
            SELECT day, SUM(cnt) AS cnt FROM (
              SELECT DATE_FORMAT(TradeTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
                FROM T_Trade
               WHERE TradeTime IS NOT NULL AND DATE(TradeTime) BETWEEN #{from} AND #{to}
               GROUP BY day
              UNION ALL
              SELECT DATE_FORMAT(TradeTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
                FROM T_Trade_his
               WHERE TradeTime IS NOT NULL AND DATE(TradeTime) BETWEEN #{from} AND #{to}
               GROUP BY day
            ) u
             GROUP BY day
             ORDER BY day
            """)
    List<DayCount> selectTradeDaily(@Param("from") String from, @Param("to") String to);

    /* ============================== 行类型 ============================== */

    /** 单个计数（条件单的 total/running/triggered 共用一行返回） */
    record CountRow(long total, long running, long triggered) {
    }

    /** 按天计数 */
    record DayCount(String day, long cnt) {
    }
}
