package com.qianlong.cloud.admin.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.UserLogEntity;

/** 云单用户登录日志 Mapper（表 {@code T_UserLog}，19.1 万行）。 */
public interface UserLogMapper extends BaseMapper<UserLogEntity> {

    /**
     * 按账号聚合（「登录频次」视图）。
     *
     * <p><b>为什么由后端聚合：</b>明细接口是分页的，前端拿明细自己 COUNT
     * 只能统计当前一页，会被当成全量读（项目里反复强调过的一点）。
     *
     * <p><b>性能说明：</b>这张表**只有主键索引**，且本项目不允许改动既有表结构
     * （不能为了这几个查询去加索引）。实测全表 GROUP BY 约 90ms、
     * 19.1 万行，对一个后台查询页可接受。若将来涨到千万级，
     * 需要与现场协商加索引或做预聚合表。
     */
    @Select("""
            <script>
            SELECT Account AS Account,
                   COUNT(*) AS cnt,
                   MIN(LogTime) AS firstTime,
                   MAX(LogTime) AS lastTime
              FROM T_UserLog
             <where>
               <if test="keyword != null and keyword != ''">
                 AND Account LIKE CONCAT('%', #{keyword}, '%')
               </if>
             </where>
             GROUP BY Account
             ORDER BY cnt DESC, Account
             LIMIT #{offset}, #{limit}
            </script>
            """)
    List<AccountStatRow> selectAccountStats(@Param("keyword") String keyword,
                                            @Param("offset") long offset,
                                            @Param("limit") long limit);

    /** 账号聚合的总数（分页用） */
    @Select("""
            <script>
            SELECT COUNT(*) FROM (
              SELECT Account FROM T_UserLog
               <where>
                 <if test="keyword != null and keyword != ''">
                   AND Account LIKE CONCAT('%', #{keyword}, '%')
                 </if>
               </where>
               GROUP BY Account
            ) t
            </script>
            """)
    long countAccountStats(@Param("keyword") String keyword);

    /** 按天统计日志条数（趋势图用；前端 mockDailyLogs 同口径） */
    @Select("""
            SELECT DATE_FORMAT(LogTime, '%Y-%m-%d') AS day, COUNT(*) AS cnt
              FROM T_UserLog
             WHERE LogTime IS NOT NULL
               AND (#{from} IS NULL OR LogTime >= #{from})
               AND (#{to}   IS NULL OR LogTime <= #{to})
             GROUP BY DATE_FORMAT(LogTime, '%Y-%m-%d')
             ORDER BY day
            """)
    List<DayCountRow> selectDaily(@Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /** 账号聚合行 */
    record AccountStatRow(String Account, Long cnt, LocalDateTime firstTime, LocalDateTime lastTime) {
    }

    /** 按天计数行 */
    record DayCountRow(String day, Long cnt) {
    }
}
