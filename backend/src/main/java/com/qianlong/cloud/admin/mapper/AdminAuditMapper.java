package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.AdminAuditEntity;

/** 管理端操作审计 Mapper。 */
public interface AdminAuditMapper extends BaseMapper<AdminAuditEntity> {

    /**
     * 按天统计审计量与高危量。
     *
     * <p><b>为什么必须由后端聚合：</b>明细接口是分页的，前端拿明细自己数只能得到
     * 「当前这一页」，却会被当成全量读（前端 README 第 9 条）。
     *
     * <p>时间范围用 {@code (#{from} IS NULL OR ...)} 而不是动态 SQL：
     * 参数为 {@code null} 即「不限」，避免拼接 SQL 带来的注入面。
     */
    @Select("""
            SELECT DATE_FORMAT(createdAt, '%Y-%m-%d') AS day,
                   SUM(CASE WHEN level = 'high' THEN 1 ELSE 0 END) AS highCount,
                   SUM(CASE WHEN level <> 'high' THEN 1 ELSE 0 END) AS otherCount
              FROM T_AdminAudit
             WHERE (#{from} IS NULL OR createdAt >= #{from})
               AND (#{to}   IS NULL OR createdAt <= #{to})
             GROUP BY DATE_FORMAT(createdAt, '%Y-%m-%d')
             ORDER BY day
            """)
    List<DayCountRow> selectDaily(@Param("from") java.time.LocalDateTime from,
                                  @Param("to") java.time.LocalDateTime to);

    /** 按动作统计（动作分布 Top N）。用 actionLabel 展示，与列表列一致。 */
    @Select("""
            SELECT actionLabel AS name, COUNT(*) AS value
              FROM T_AdminAudit
             WHERE (#{from} IS NULL OR createdAt >= #{from})
               AND (#{to}   IS NULL OR createdAt <= #{to})
             GROUP BY actionLabel
             ORDER BY value DESC, name
             LIMIT #{limit}
            """)
    List<NameCountRow> selectByAction(@Param("from") java.time.LocalDateTime from,
                                      @Param("to") java.time.LocalDateTime to,
                                      @Param("limit") int limit);

    /** 按天聚合的原始行 */
    record DayCountRow(String day, Long highCount, Long otherCount) {
    }

    /** 通用「名称 → 计数」原始行 */
    record NameCountRow(String name, Long value) {
    }
}
