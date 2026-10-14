package com.qianlong.cloud.admin.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qianlong.cloud.admin.entity.UserAccountEntity;

/** 云单业务用户 Mapper（表 {@code T_User}）。 */
public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {

    /**
     * 查出全部账号（用于与 Redis acc-* 做合并）。
     *
     * <p><b>为什么不分页查这张表：</b>合并逻辑是「以两边键的并集为准」——
     * 必须先拿到 MySQL 侧全部账号、Redis 侧全部键，才能算出并集，
     * 再对并集做筛选与分页。若先分页再合并，
     * 会出现「第 2 页里混进第 1 页的账号」这种无法解释的结果。
     *
     * <p>现场这张表只有 1 行，全量读没有代价。
     * ⚠️ 但它有 AUTO_INCREMENT=160707，说明历史上写过多行；
     * 若将来真涨到几十万行，这里要改成「先按条件在两边各取候选、再归并」的策略，
     * 不能简单全量读。已在 AccountService 里留了说明。
     */
    @Select("SELECT * FROM T_User")
    List<UserAccountEntity> selectAllForMerge();

    /** 按唯一账号精确取一条（详情接口用） */
    @Select("SELECT * FROM T_User WHERE Account = #{account} LIMIT 1")
    UserAccountEntity selectByAccount(@Param("account") String account);
}
