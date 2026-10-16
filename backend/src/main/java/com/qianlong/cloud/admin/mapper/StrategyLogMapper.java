package com.qianlong.cloud.admin.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.StrategyLogEntity;

/**
 * 策略日志 Mapper（表 {@code T_StrategyLog}，17430 行）。
 *
 * <h2>⚠️ 两个必须避开的坑</h2>
 *
 * <p><b>1. 日期列存了非法的空字符串，不能用 {@code <> ''} 比较。</b>
 * {@code OccurTIme} 是 {@code date} 类型，但库里存过 {@code ''}；
 * 一旦写出 {@code WHERE OccurTIme <> ''}，
 * MySQL 会报 <b>1525 (Incorrect DATE value: '')</b> ——
 * 整条查询直接失败，而不是「筛掉几行」。
 * 所以本 Mapper 对日期列**只用 {@code IS NULL} 与范围比较**。
 * （实测：{@code OccurTIme IS NULL} = 0 行，{@code MIN/MAX} 正常。）
 *
 * <p><b>2. 表上只有主键索引</b>（实测 {@code SHOW INDEX} 只有 PRIMARY），
 * 且本项目不允许改动既有表结构（不能加索引）。
 * 实测耗时：主键倒序分页 ~1ms、按 Strategy 过滤 ~7ms、
 * 按天聚合 ~6ms、{@code Info LIKE} ~1ms —— 1.7 万行下都可接受。
 *
 * <h2>为什么关键字搜索不查 Status / FromIP</h2>
 * 这两列现场**整列都是 NULL**，把它们放进 {@code LIKE} 条件没有意义
 * （永远匹配不到），反而让 SQL 更长、更难看出「这个字段其实没数据」。
 * 页面上的输入框若指向它们，应该直接提示「该列现场为空」，
 * 而不是让人以为「搜了但没有」。
 */
public interface StrategyLogMapper extends BaseMapper<StrategyLogEntity> {

    /**
     * 分页查询策略日志。
     *
     * <p>筛选口径与前端 {@code currentParams()} 一致：
     * {@code keyword}（策略编号 / 日志内容 / 来源账号）、
     * {@code status}、{@code fromIp}，外加可选的时间范围。
     */
    @Select("""
            <script>
            SELECT * FROM T_StrategyLog
            <where>
              <if test="keyword != null and keyword != ''">
                AND (CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                  OR Info LIKE CONCAT('%', #{keyword}, '%')
                  OR FromAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND Status = #{status}
              </if>
              <if test="fromIp != null and fromIp != ''">
                AND FromIP LIKE CONCAT('%', #{fromIp}, '%')
              </if>
              <!-- 日期列只用 IS NULL / 范围比较，绝不用 &lt;&gt; ''（会触发 MySQL 1525） -->
              <if test="from != null">AND OccurTIme &gt;= #{from}</if>
              <if test="to != null">AND OccurTIme &lt;= #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<StrategyLogEntity> selectPage(@Param("keyword") String keyword,
                                       @Param("status") String status,
                                       @Param("fromIp") String fromIp,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       @Param("offset") long offset,
                                       @Param("limit") long limit);

    /** 同条件计数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM T_StrategyLog
            <where>
              <if test="keyword != null and keyword != ''">
                AND (CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                  OR Info LIKE CONCAT('%', #{keyword}, '%')
                  OR FromAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND Status = #{status}
              </if>
              <if test="fromIp != null and fromIp != ''">
                AND FromIP LIKE CONCAT('%', #{fromIp}, '%')
              </if>
              <if test="from != null">AND OccurTIme &gt;= #{from}</if>
              <if test="to != null">AND OccurTIme &lt;= #{to}</if>
            </where>
            </script>
            """)
    long countPage(@Param("keyword") String keyword,
                   @Param("status") String status,
                   @Param("fromIp") String fromIp,
                   @Param("from") LocalDate from,
                   @Param("to") LocalDate to);

    /**
     * {@code Status} 列的真实去重取值。
     *
     * <p><b>为什么必须提供这个接口：</b>前端页面的状态下拉目前取自
     * {@code mock/strategy.ts} 的 {@code LOG_STATUS}
     * （触发成功 / 触发失败 / 已暂停 / 已恢复 / 已删除 / 校验失败 / 参数错误）——
     * 那是**演示数据编的**。现场 {@code Status} 整列为 NULL，
     * 那 7 个值一个都不存在。让人从 mock 里取选项，会造成
     * 「下拉里有 7 个选项、选了每个都查出 0 条」这种极具误导性的界面。
     *
     * <p>这里如实返回去重结果（现在是**空数组**），
     * 前端据此渲染「状态列现场为空」而不是假的 7 个选项。
     *
     * <p>用 {@code IS NOT NULL} 顺带排掉 NULL，避免返回一个无意义的 {@code null} 项。
     */
    @Select("SELECT DISTINCT Status FROM T_StrategyLog WHERE Status IS NOT NULL ORDER BY Status")
    List<String> selectDistinctStatuses();

    /**
     * 按天统计日志量（趋势图）。
     *
     * <p>⚠️ 只按 {@code OccurTIme} 分组，且**不加** {@code <> ''} 条件
     * —— 见类注释的 1525 报错说明。非法空串行会被 {@code DATE()} 处理成 NULL
     * 从而自然落在「不是有效日期」的分组里；这里干脆用
     * {@code OccurTIme IS NOT NULL} 过滤（实测该列无 NULL，语句恒真但语义清晰）。
     */
    @Select("""
            SELECT OccurTIme AS day, COUNT(*) AS cnt
              FROM T_StrategyLog
             WHERE OccurTIme IS NOT NULL
               AND (#{from} IS NULL OR OccurTIme >= #{from})
               AND (#{to}   IS NULL OR OccurTIme <= #{to})
             GROUP BY OccurTIme
             ORDER BY day
            """)
    List<DayCountRow> selectDaily(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 按 {@code Strategy}（条件单）聚合，用于「按策略单查看生命周期」。
     *
     * <p>由后端 GROUP BY：前端拿分页明细自己数只能得到当前页。
     */
    @Select("""
            <script>
            SELECT Strategy AS strategy, COUNT(*) AS cnt,
                   MIN(OccurTIme) AS firstDay, MAX(OccurTIme) AS lastDay
              FROM T_StrategyLog
             <where>
               <if test="keyword != null and keyword != ''">
                 AND CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
               </if>
             </where>
             GROUP BY Strategy
             ORDER BY cnt DESC, Strategy
             LIMIT #{offset}, #{limit}
            </script>
            """)
    List<StrategyStatRow> selectStrategyStats(@Param("keyword") String keyword,
                                              @Param("offset") long offset,
                                              @Param("limit") long limit);

    /** 按 Strategy 聚合的总组数 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM (
              SELECT Strategy FROM T_StrategyLog
              <where>
                <if test="keyword != null and keyword != ''">
                  AND CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                </if>
              </where>
              GROUP BY Strategy
            ) t
            </script>
            """)
    long countStrategyStats(@Param("keyword") String keyword);

    /**
     * 某条件单的完整日志轨迹（详情抽屉的时间线用）。
     *
     * <p>走 {@code Strategy} 过滤，实测 ~7ms。
     * 有硬上限，避免某个异常条件单挂了上万条日志把内存打爆。
     */
    @Select("""
            SELECT * FROM T_StrategyLog
             WHERE Strategy = #{strategy}
             ORDER BY OccurTIme ASC, id ASC
             LIMIT #{limit}
            """)
    List<StrategyLogEntity> selectTimeline(@Param("strategy") Long strategy,
                                           @Param("limit") int limit);

    /**
     * 策略日志汇总（全量口径，卡片用）。
     *
     * <p>原先「触发成功 / 触发失败 / 成功率 / 来源账号数」是对当前页算的，
     * 翻页就变。这里由数据库算准。
     *
     * <p><b>⚠️ 现场 {@code Status} 整列为 NULL</b>，所以成功/失败计数
     * 现在都是 0 —— 这是事实，不是查询写错。前端应据此显示
     * 「该列现场为空」而不是一个假的 0% 成功率。
     * 判成功/失败用的取值与前端 {@code statusType()} 一致：
     * 成功 = 触发成功 / 已恢复；失败 = 触发失败 / 校验失败 / 参数错误。
     *
     * <p>筛选条件与 {@code countPage} 一致，保证与列表总数对得上。
     */
    @Select("""
            <script>
            SELECT
              COALESCE(SUM(CASE WHEN Status IN ('触发成功','已恢复') THEN 1 ELSE 0 END), 0) AS okCount,
              COALESCE(SUM(CASE WHEN Status IN ('触发失败','校验失败','参数错误') THEN 1 ELSE 0 END), 0) AS failCount,
              COUNT(DISTINCT FromAccount) AS sourceCount,
              COUNT(*) AS totalCount
            FROM T_StrategyLog
            <where>
              <if test="keyword != null and keyword != ''">
                AND (CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                  OR Info LIKE CONCAT('%', #{keyword}, '%')
                  OR FromAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND Status = #{status}
              </if>
              <if test="fromIp != null and fromIp != ''">
                AND FromIP LIKE CONCAT('%', #{fromIp}, '%')
              </if>
              <if test="from != null">AND OccurTIme &gt;= #{from}</if>
              <if test="to != null">AND OccurTIme &lt;= #{to}</if>
            </where>
            </script>
            """)
    StrategyLogStatsRow selectStats(@Param("keyword") String keyword,
                                    @Param("status") String status,
                                    @Param("fromIp") String fromIp,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    record DayCountRow(LocalDate day, Long cnt) {
    }

    record StrategyStatRow(Long strategy, Long cnt, LocalDate firstDay, LocalDate lastDay) {
    }

    /**
     * 按 {@code Status} 分组统计（状态分布柱状图，**全量口径**）。
     *
     * <p>原先前端拿当前页明细自己分组 —— 那只是「这一页的状态分布」，翻页就变。
     *
     * <p>现场 {@code Status} 整列为 NULL，所以现在只返回一行
     * {@code status=null, cnt=全表条数}。前端把 null 显示成「未知」是诚实的，
     * 同时 {@code statusColumnEmpty} 会提示「该列现场为空」。
     */
    @Select("""
            <script>
            SELECT Status AS status, COUNT(*) AS cnt
              FROM T_StrategyLog
            <where>
              <if test="keyword != null and keyword != ''">
                AND (CAST(Strategy AS CHAR) LIKE CONCAT('%', #{keyword}, '%')
                  OR Info LIKE CONCAT('%', #{keyword}, '%')
                  OR FromAccount LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND Status = #{status}
              </if>
              <if test="fromIp != null and fromIp != ''">
                AND FromIP LIKE CONCAT('%', #{fromIp}, '%')
              </if>
              <if test="from != null">AND OccurTIme &gt;= #{from}</if>
              <if test="to != null">AND OccurTIme &lt;= #{to}</if>
            </where>
             GROUP BY Status
             ORDER BY cnt DESC
            </script>
            """)
    List<StatusCountRow> selectStatusDistribution(@Param("keyword") String keyword,
                                                  @Param("status") String status,
                                                  @Param("fromIp") String fromIp,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** 按 Status 分组的计数（status 可能为 null，表示该列此行为空） */
    record StatusCountRow(String status, Long cnt) {
    }

    /** 策略日志汇总原始行 */
    record StrategyLogStatsRow(Long okCount,
                               Long failCount,
                               Long sourceCount,
                               Long totalCount) {
    }
}
