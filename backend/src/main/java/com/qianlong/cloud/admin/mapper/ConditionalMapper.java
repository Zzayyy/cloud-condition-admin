package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.qianlong.cloud.admin.entity.ConditionalEntity;

/**
 * 条件单 Mapper —— 覆盖「三张表 + 一个视图」。
 *
 * <h2>⚠️ 表名只能用 {@code ${}}，因此必须自己保证安全</h2>
 * 三张表列结构完全相同，只有表名不同。MyBatis 的预编译参数 {@code #{}} 只能出现在
 * **值**的位置，表名位置必须用 {@code ${}} 拼接 —— 这是个注入面。
 *
 * <p>两层防护（缺一不可）：
 * <ol>
 *   <li><b>调用方只能传 {@code ConditionalSource.table()}</b>，
 *       它只会返回下面 {@code <choose>} 里写死的那三个常量之一；</li>
 *   <li><b>SQL 里再白名单校验一次</b>（纵深防御）：不匹配就落到
 *       {@code WHERE 1=0}，查不到任何数据但**不会报错暴露结构**，
 *       也不会执行任何拼接进来的东西。</li>
 * </ol>
 * 之所以不在 Java 里做白名单就算完，是因为这类「拼接进 SQL 的标识符」
 * 一旦有人绕过 service 直接调 mapper 就失守了；写在 SQL 里更靠近危险点。
 *
 * <p><b>不提供任何写方法</b>：条件单的启停/删除要走 set / remove 协议
 * 下发到策略平台（另一条链路，需要数据中心配合）。直接改库不会生效，
 * 反而会让库与 Redis 不一致 —— 所以本 Mapper 只读。
 */
public interface ConditionalMapper {

    /** 只允许的三种数据源（与 ConditionalSource 一一对应） */
    String T_RUNNING = "T_Conditional";
    String T_HISTORY = "T_Conditional_his";
    String T_ALL = "V_Conditional";

    @Select("""
            <script>
            SELECT * FROM
            <choose>
              <when test="table == 'T_Conditional'">T_Conditional</when>
              <when test="table == 'T_Conditional_his'">T_Conditional_his</when>
              <when test="table == 'V_Conditional'">V_Conditional</when>
              <otherwise>(SELECT NULL AS id FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%')
                  OR Name LIKE CONCAT('%', #{keyword}, '%')
                  OR Description LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="type != null">AND Type = #{type}</if>
              <if test="mode != null">AND Mode = #{mode}</if>
            </where>
            ORDER BY AddTime DESC, id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<ConditionalEntity> selectPage(@Param("table") String table,
                                       @Param("keyword") String keyword,
                                       @Param("type") Integer type,
                                       @Param("mode") Integer mode,
                                       @Param("offset") long offset,
                                       @Param("limit") long limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM
            <choose>
              <when test="table == 'T_Conditional'">T_Conditional</when>
              <when test="table == 'T_Conditional_his'">T_Conditional_his</when>
              <when test="table == 'V_Conditional'">V_Conditional</when>
              <otherwise>(SELECT NULL AS id FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            <where>
              <if test="keyword != null and keyword != ''">
                AND (Account LIKE CONCAT('%', #{keyword}, '%')
                  OR UserAccount LIKE CONCAT('%', #{keyword}, '%')
                  OR Name LIKE CONCAT('%', #{keyword}, '%')
                  OR Description LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="type != null">AND Type = #{type}</if>
              <if test="mode != null">AND Mode = #{mode}</if>
            </where>
            </script>
            """)
    long countPage(@Param("table") String table,
                   @Param("keyword") String keyword,
                   @Param("type") Integer type,
                   @Param("mode") Integer mode);

    /** 按 id 在指定表/视图里查单条 */
    @Select("""
            <script>
            SELECT * FROM
            <choose>
              <when test="table == 'T_Conditional'">T_Conditional</when>
              <when test="table == 'T_Conditional_his'">T_Conditional_his</when>
              <when test="table == 'V_Conditional'">V_Conditional</when>
              <otherwise>(SELECT NULL AS id FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
            WHERE id = #{id} LIMIT 1
            </script>
            """)
    ConditionalEntity selectByIdIn(@Param("table") String table, @Param("id") Long id);

    /**
     * 按天统计新增条件单（趋势图用）。
     *
     * <p>现场这三张表都是 0 行，所以这个查询目前返回空列表 ——
     * 图表会如实显示「暂无数据」。不用别处的数据充数。
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(AddTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
              FROM
            <choose>
              <when test="table == 'T_Conditional'">T_Conditional</when>
              <when test="table == 'T_Conditional_his'">T_Conditional_his</when>
              <when test="table == 'V_Conditional'">V_Conditional</when>
              <otherwise>(SELECT NULL AS AddTime FROM DUAL WHERE 1=0) dummy</otherwise>
            </choose>
             WHERE AddTime IS NOT NULL
               AND (#{from} IS NULL OR AddTime &gt;= #{from})
               AND (#{to}   IS NULL OR AddTime &lt;= #{to})
             GROUP BY DATE_FORMAT(AddTime, '%Y-%m-%d')
             ORDER BY day
            </script>
            """)
    List<DayCountRow> selectDaily(@Param("table") String table,
                                  @Param("from") java.time.LocalDateTime from,
                                  @Param("to") java.time.LocalDateTime to);

    record DayCountRow(String day, Long cnt) {
    }
}
