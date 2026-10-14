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
}
