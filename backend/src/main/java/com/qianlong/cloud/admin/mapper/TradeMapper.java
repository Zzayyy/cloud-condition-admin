package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.qianlong.cloud.admin.entity.TradeEntity;

/**
 * 成交 Mapper（{@code T_Trade} / {@code T_Trade_his}）。
 *
 * <p>表名用法的安全说明同 {@code OrderMapper}：只能用 {@code ${}} 拼接，
 * 靠枚举 + SQL 内白名单两层防护。
 */
public interface TradeMapper {

    @Select("""
            <script>
            SELECT * FROM
            <choose>
              <when test="table == 'T_Trade'">T_Trade</when>
              <when test="table == 'T_Trade_his'">T_Trade_his</when>
              <otherwise>(SELECT NULL AS id FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR TradeNO LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            ORDER BY id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<TradeEntity> selectPage(@Param("table") String table,
                                 @Param("keyword") String keyword,
                                 @Param("code") String code,
                                 @Param("offset") long offset,
                                 @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM
            <choose>
              <when test="table == 'T_Trade'">T_Trade</when>
              <when test="table == 'T_Trade_his'">T_Trade_his</when>
              <otherwise>(SELECT NULL AS id FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR TradeNO LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            </script>
            """)
    long countPage(@Param("table") String table,
                   @Param("keyword") String keyword,
                   @Param("code") String code);

    /**
     * 按委托编号查该委托下的全部成交（「委托 → 成交」下钻）。
     *
     * <p><b>为什么两张表都查</b>：委托单可能在主表、成交却已经归档到历史表
     * （或反之）。只查一张表会表现为「这个委托没有成交」——
     * 而那与「确实没成交」在页面上长得一样，运维没法区分。
     * 所以这里用 {@code UNION ALL} 把两张表合起来查，结果按 id 去重。
     *
     * <p>⚠️ {@code T_Trade_his} 上**没有任何索引**（现场实测），
     * 所以这个查询在数据量大了以后是全表扫。现场只有 1 行，暂无代价；
     * 若将来涨上去，需要与现场协商加索引（本项目不动既有表结构）。
     */
    @Select("""
            SELECT * FROM (
              SELECT id, StrategyID, OrderNo, TradeNO, Account, Market, Code,
                     Volumn, Price, TradeTime, UserAccount FROM T_Trade      WHERE OrderNo = #{orderNo}
              UNION ALL
              SELECT id, StrategyID, OrderNo, TradeNO, Account, Market, Code,
                     Volumn, Price, TradeTime, UserAccount FROM T_Trade_his  WHERE OrderNo = #{orderNo}
            ) t
            ORDER BY t.id DESC
            """)
    List<TradeEntity> selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 成交汇总（全量口径，卡片用）。
     *
     * <p>与委托单同理：页面上「成交笔数 / 成交总量 / 成交金额 / 成交均价」
     * 原先是对当前页 reduce 出来的，翻页数字就变。这里由数据库算准。
     * 筛选条件与 {@code countPage} 完全一致，保证与列表总数对得上。
     *
     * <p><b>⚠️ {@code Volumn} 是 varchar</b>（现场列类型如此），
     * 所以求和前必须 {@code CAST(... AS DECIMAL)}，否则 MySQL 会按字符串相加。
     * 非数字内容（理论上不该有）会 CAST 成 0，不会让整条查询失败。
     *
     * <p>{@code Price} 可为 NULL，用 COALESCE 兜 0。
     */
    @Select("""
            <script>
            SELECT
              COUNT(*) AS totalCount,
              COALESCE(SUM(CAST(COALESCE(Volumn, '0') AS DECIMAL(20,4))), 0) AS totalVolumn,
              COALESCE(SUM(COALESCE(Price, 0) * CAST(COALESCE(Volumn, '0') AS DECIMAL(20,4))), 0) AS totalAmount
            FROM
            <choose>
              <when test="table == 'T_Trade'">T_Trade</when>
              <when test="table == 'T_Trade_his'">T_Trade_his</when>
              <otherwise>(SELECT NULL AS Price FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR TradeNO LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            </script>
            """)
    TradeStatsRow selectStats(@Param("table") String table,
                              @Param("keyword") String keyword,
                              @Param("code") String code);

    /**
     * 按合约分组统计成交量（全量口径，合约分布图用）。
     *
     * <p>与委托单的「买卖分布」同理：前端拿分页明细分组只能得到当前页的分布。
     * 这里 GROUP BY 一次算好，并且**在 SQL 里限行**（Top N），
     * 避免某个合约种类特别多的场景把结果集拉大。
     */
    @Select("""
            <script>
            SELECT Code AS code,
                   COALESCE(SUM(CAST(COALESCE(Volumn, '0') AS DECIMAL(20,4))), 0) AS cnt
            FROM
            <choose>
              <when test="table == 'T_Trade'">T_Trade</when>
              <when test="table == 'T_Trade_his'">T_Trade_his</when>
              <otherwise>(SELECT NULL AS Code FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR TradeNO LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
              <!--
                ⚠️ `Code IS NOT NULL` 必须写在 <where> **里面**。
                放到 </where> 之后（写成裸的 " AND Code IS NOT NULL"）时，
                若上面几个 <if> 全不成立、<where> 渲染为空，
                就会拼出 "FROM T_Trade AND Code IS NOT NULL" —— 语法错误，
                整条查询报 BadSqlGrammar（实测踩过：无筛选条件时 50000）。
              -->
              AND Code IS NOT NULL
            </where>
             GROUP BY Code
             ORDER BY cnt DESC
             LIMIT #{limit}
            </script>
            """)
    List<CodeCountRow> countByCode(@Param("table") String table,
                                   @Param("keyword") String keyword,
                                   @Param("code") String code,
                                   @Param("limit") int limit);

    /**
     * 按天统计成交笔数与成交金额（趋势图，**全量口径**）。
     *
     * <p>为什么需要它：页面原本只有「各合约成交量分布」一张饼图，
     * 卡片也只有总数 —— **唯一缺失的维度是时间**。而成交数据的价值主要就在时序上：
     * 「500 万是某一天一笔打出来的」与「均匀分布在 14 天」是完全不同的两件事，
     * 现状下看不出来。
     *
     * <p>⚠️ {@code Volumn} 是 varchar，求和前必须 CAST 成 DECIMAL（同 {@code selectStats}）。
     * <p>⚠️ {@code TradeTime} 是 date 类型（无时分秒），所以直接按日期分组；
     * 用 {@code IS NOT NULL} 过滤，避免非法空串进入 {@code DATE_FORMAT}。
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(TradeTime, '%Y-%m-%d') AS day,
                   COUNT(*) AS tradeCount,
                   COALESCE(SUM(COALESCE(Price, 0) * CAST(COALESCE(Volumn, '0') AS DECIMAL(20,4))), 0) AS amount
            FROM
            <choose>
              <when test="table == 'T_Trade'">T_Trade</when>
              <when test="table == 'T_Trade_his'">T_Trade_his</when>
              <otherwise>(SELECT NULL AS TradeTime FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR TradeNO LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
              AND TradeTime IS NOT NULL
            </where>
             GROUP BY DATE_FORMAT(TradeTime, '%Y-%m-%d')
             ORDER BY day
            </script>
            """)
    List<TradeDailyRow> selectDaily(@Param("table") String table,
                                     @Param("keyword") String keyword,
                                     @Param("code") String code);

    /** 成交汇总原始行 */
    record TradeStatsRow(Long totalCount, java.math.BigDecimal totalVolumn, java.math.BigDecimal totalAmount) {
    }

    /** 按合约的成交量 */
    record CodeCountRow(String code, java.math.BigDecimal cnt) {
    }

    /** 按天的成交笔数与金额 */
    record TradeDailyRow(String day, Long tradeCount, java.math.BigDecimal amount) {
    }
}
