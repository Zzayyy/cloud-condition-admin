package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.AdminLoginLogEntity;

/** 管理端登录日志 Mapper（**不是**云单客户的 T_UserLog）。 */
public interface AdminLoginLogMapper extends BaseMapper<AdminLoginLogEntity> {

    /** 按天统计登录成功 / 失败（后端聚合，前端不做统计） */
    @Select("""
            SELECT DATE_FORMAT(loginTime, '%Y-%m-%d') AS day,
                   SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCount,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failureCount
              FROM T_AdminLoginLog
             WHERE (#{from} IS NULL OR loginTime >= #{from})
               AND (#{to}   IS NULL OR loginTime <= #{to})
             GROUP BY DATE_FORMAT(loginTime, '%Y-%m-%d')
             ORDER BY day
            """)
    List<DayCountRow> selectDaily(@Param("from") java.time.LocalDateTime from,
                                  @Param("to") java.time.LocalDateTime to);

    /**
     * 登录失败按来源 IP 聚合（Top N）。
     *
     * <p>只统计 {@code success = 0} 的行；{@code loginIP} 为空的归到「未知」，
     * 不丢弃（丢掉的失败记录会让「异常来源」看着比实际少）。
     */
    @Select("""
            SELECT COALESCE(NULLIF(loginIP, ''), '未知') AS name, COUNT(*) AS value
              FROM T_AdminLoginLog
             WHERE success = 0
               AND (#{from} IS NULL OR loginTime >= #{from})
               AND (#{to}   IS NULL OR loginTime <= #{to})
             GROUP BY COALESCE(NULLIF(loginIP, ''), '未知')
             ORDER BY value DESC, name
             LIMIT #{limit}
            """)
    List<NameCountRow> selectFailByIp(@Param("from") java.time.LocalDateTime from,
                                      @Param("to") java.time.LocalDateTime to,
                                      @Param("limit") int limit);

    /**
     * 登录日志汇总（**全量口径**，卡片用）。
     *
     * <p>与列表同筛选条件。原来是对当前页明细统计，翻页就变。
     *
     * <p>⚠️ 「结果」筛选是布尔：前端传的是 {@code true}/{@code false}
     * （不是审计那套 {@code success}/{@code failure}）。
     * 这里用 {@code Boolean} 接收并由 MyBatis 的 {@code <if test="success != null">} 判断 ——
     * 不能用 {@code success != ''}，布尔和空串比较会出问题。
     */
    @Select("""
            <script>
            SELECT
              COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS successCount,
              COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failureCount,
              COUNT(*) AS totalCount
            FROM T_AdminLoginLog
            <where>
              <if test="keyword != null and keyword != ''">
                AND (username LIKE CONCAT('%', #{keyword}, '%')
                  OR loginIP LIKE CONCAT('%', #{keyword}, '%')
                  OR loginOS LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="username != null and username != ''">
                AND username LIKE CONCAT('%', #{username}, '%')
              </if>
              <if test="ip != null and ip != ''">AND loginIP LIKE CONCAT('%', #{ip}, '%')</if>
              <if test="success != null">AND success = #{success}</if>
              <if test="from != null">AND loginTime &gt;= #{from}</if>
              <if test="to != null">AND loginTime &lt;= #{to}</if>
            </where>
            </script>
            """)
    LoginStatsRow selectStats(@Param("keyword") String keyword,
                              @Param("username") String username,
                              @Param("ip") String ip,
                              @Param("success") Boolean success,
                              @Param("from") java.time.LocalDateTime from,
                              @Param("to") java.time.LocalDateTime to);

    record DayCountRow(String day, Long successCount, Long failureCount) {
    }

    record NameCountRow(String name, Long value) {
    }

    /** 登录日志汇总原始行 */
    record LoginStatsRow(Long successCount, Long failureCount, Long totalCount) {
    }
}
