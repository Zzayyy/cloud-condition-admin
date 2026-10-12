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

    record DayCountRow(String day, Long successCount, Long failureCount) {
    }

    record NameCountRow(String name, Long value) {
    }
}
