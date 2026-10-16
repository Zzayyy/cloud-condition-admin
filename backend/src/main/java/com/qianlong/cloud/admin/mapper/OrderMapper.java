package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.qianlong.cloud.admin.entity.OrderEntity;

/**
 * 委托单 Mapper（{@code T_Order} / {@code T_Order_his}）。
 *
 * <h2>⚠️ 表名只能用 {@code ${}}，因此必须自己保证安全</h2>
 * 两张表列结构相同、只有表名不同，而 MyBatis 的 {@code #{}} 只能出现在**值**的
 * 位置，表名位置必须用 {@code ${}} 拼接 —— 这是注入面。
 *
 * <p>而且**前端的表名直接来自用户可点的 tab**
 * （{@code table: tab === 'active' ? 'T_Order' : 'T_Order_his'}），
 * 所以这个字符串绝不能被直接信任。
 *
 * <p>两层防护：
 * <ol>
 *   <li>调用方只能传 {@code TradeTable.table()}，枚举里写死了四个常量；</li>
 *   <li>SQL 里再白名单校验一次（纵深防御），不匹配就落到 {@code WHERE 1=0}，
 *       查不到数据但不会执行任何拼接进来的东西。</li>
 * </ol>
 *
 * <p><b>不提供写方法</b>：委托单由策略平台产生，后台不修改它。
 */
public interface OrderMapper {

    @Select("""
            <script>
            SELECT * FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS ID FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR CodeName LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%')
                  OR CAST(StrategyID AS CHAR) LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="bsType != null">AND BSType = #{bsType}</if>
              <if test="ocType != null">AND OCType = #{ocType}</if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            ORDER BY ID DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<OrderEntity> selectPage(@Param("table") String table,
                                 @Param("keyword") String keyword,
                                 @Param("bsType") Integer bsType,
                                 @Param("ocType") Integer ocType,
                                 @Param("code") String code,
                                 @Param("offset") long offset,
                                 @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS ID FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR CodeName LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%')
                  OR CAST(StrategyID AS CHAR) LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="bsType != null">AND BSType = #{bsType}</if>
              <if test="ocType != null">AND OCType = #{ocType}</if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            </script>
            """)
    long countPage(@Param("table") String table,
                   @Param("keyword") String keyword,
                   @Param("bsType") Integer bsType,
                   @Param("ocType") Integer ocType,
                   @Param("code") String code);

    /**
     * 按 id 在指定表里查单条。
     *
     * <p>主键列名两张表都叫 {@code ID}。
     */
    @Select("""
            <script>
            SELECT * FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS ID FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            WHERE ID = #{id} LIMIT 1
            </script>
            """)
    OrderEntity selectByIdIn(@Param("table") String table, @Param("id") Long id);

    /**
     * 按买卖方向分组统计（买卖分布图，全量口径）。
     *
     * <p>由后端 GROUP BY 一次算好 —— 前端拿分页明细自己数只能得到当前页。
     */
    @Select("""
            <script>
            SELECT BSType AS bsType, COUNT(*) AS cnt FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS BSType FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            GROUP BY BSType
            </script>
            """)
    List<BsCountRow> countByBsType(@Param("table") String table);

    /** 按天统一下单量（趋势图） */
    @Select("""
            <script>
            SELECT DATE_FORMAT(OrderTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS OrderTime FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
             WHERE OrderTime IS NOT NULL
               AND (#{from} IS NULL OR OrderTime &gt;= #{from})
               AND (#{to}   IS NULL OR OrderTime &lt;= #{to})
             GROUP BY DATE_FORMAT(OrderTime, '%Y-%m-%d')
             ORDER BY day
            </script>
            """)
    List<DayCountRow> selectDaily(@Param("table") String table,
                                  @Param("from") java.time.LocalDate from,
                                  @Param("to") java.time.LocalDate to);

    /**
     * 委托单汇总（全量口径，卡片用）。
     *
     * <p><b>为什么要单独做这个接口</b>：页面上那几张卡片（买入/卖出委托笔数、
     * 委托金额）原先是对**当前页**的 {@code rows} 做 filter / reduce 算出来的，
     * 翻一页数字就变一次，而标签上写的是「买入委托」这种像是全量的说法 ——
     * 同一个标签在不同页给出不同数字，且没有总数可对照，会被读成总量。
     * 这里由数据库一次算准。
     *
     * <p>筛选条件与 {@code countPage} **完全一致**（keyword / bsType / ocType / code），
     * 这样卡片数字与列表「共 N 条」始终对得上；只多一个 {@code table} 用来选主表或历史表。
     *
     * <p>{@code SUM(OrderPrice * OrderVolumn)} 里 {@code OrderPrice} 可为 NULL，
     * 用 {@code COALESCE} 兜成 0，否则整行结果为 NULL 而不是「部分有效」。
     */
    @Select("""
            <script>
            SELECT
              COALESCE(SUM(CASE WHEN BSType = 1 THEN 1 ELSE 0 END), 0) AS buyCount,
              COALESCE(SUM(CASE WHEN BSType = 2 THEN 1 ELSE 0 END), 0) AS sellCount,
              COALESCE(SUM(CASE WHEN Reason IS NOT NULL AND Reason &lt;&gt; '' THEN 1 ELSE 0 END), 0) AS abnormalCount,
              COALESCE(SUM(COALESCE(OrderPrice, 0) * COALESCE(OrderVolumn, 0)), 0) AS totalAmount,
              COUNT(*) AS totalCount
            FROM
            <choose>
              <when test="table == 'T_Order'">T_Order</when>
              <when test="table == 'T_Order_his'">T_Order_his</when>
              <otherwise>(SELECT NULL AS BSType FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR Code LIKE CONCAT('%', #{keyword}, '%')
                  OR CodeName LIKE CONCAT('%', #{keyword}, '%')
                  OR OrderNo LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%')
                  OR CAST(StrategyID AS CHAR) LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="bsType != null">AND BSType = #{bsType}</if>
              <if test="ocType != null">AND OCType = #{ocType}</if>
              <if test="code != null and code != ''">AND Code LIKE CONCAT('%', #{code}, '%')</if>
            </where>
            </script>
            """)
    OrderStatsRow selectStats(@Param("table") String table,
                              @Param("keyword") String keyword,
                              @Param("bsType") Integer bsType,
                              @Param("ocType") Integer ocType,
                              @Param("code") String code);

    record BsCountRow(Integer bsType, Long cnt) {
    }

    record DayCountRow(String day, Long cnt) {
    }

    /** 委托单汇总的原始行（字段名与 SQL 别名一致） */
    record OrderStatsRow(Long buyCount,
                         Long sellCount,
                         Long abnormalCount,
                         Double totalAmount,
                         Long totalCount) {
    }
}
