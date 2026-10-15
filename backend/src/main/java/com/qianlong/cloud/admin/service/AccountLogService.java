package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountLogStatView;
import com.qianlong.cloud.admin.dto.AccountDtos.LogQuery;
import com.qianlong.cloud.admin.dto.AccountDtos.UserLogDailyRow;
import com.qianlong.cloud.admin.dto.AccountDtos.UserLogView;
import com.qianlong.cloud.admin.entity.UserLogEntity;
import com.qianlong.cloud.admin.mapper.UserLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 云单用户登录日志（表 {@code T_UserLog}，19.1 万行）。
 *
 * <h2>⚠️ 这张表有两个必须如实呈现的现场事实（不要「修好」它们）</h2>
 * <ol>
 *   <li><b>{@code LogIP} / {@code LogMAC} / {@code LogOS} 三列全部为 NULL</b>
 *       （实测 191009 行无例外）。所以页面的「只看有设备信息的」筛选
 *       必然查出 0 条 —— 那是真的没有数据，不是查询写错了。
 *       后端要如实返回 0，前端也已经写明了这一点。</li>
 *   <li><b>{@code Info} 实际只有 {@code user registed} /
 *       {@code user insert or updated} 两类取值</b>，不承载「失败原因」语义。</li>
 * </ol>
 *
 * <h2>性能</h2>
 * 表上**只有主键索引**（实测 SHOW INDEX 只有 PRIMARY），
 * 且本项目不允许改动既有表结构（因此不能加索引）。
 * 实测 19.1 万行下：主键倒序分页 ~1ms，{@code Account LIKE} ~106ms，
 * {@code GROUP BY Account} ~90ms。对后台查询页可接受。
 * 若涨到千万级需要与现场协商加索引或做预聚合。
 */
@Service
@RequiredArgsConstructor
public class AccountLogService {

    private final UserLogMapper userLogMapper;

    /** 分页查登录日志 */
    public PageResult<UserLogView> listLogs(LogQuery query) {
        LambdaQueryWrapper<UserLogEntity> wrapper = Wrappers.lambdaQuery();

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(UserLogEntity::getAccount, keyword)
                    .or().like(UserLogEntity::getInfo, keyword));
        }
        String account = trimToNull(query.getAccount());
        if (account != null) {
            wrapper.like(UserLogEntity::getAccount, account);
        }
        String info = trimToNull(query.getInfo());
        if (info != null) {
            wrapper.like(UserLogEntity::getInfo, info);
        }

        /*
         * 「设备信息有无」筛选。
         *
         * 现场三列全 NULL，所以 withDevice=true 恒返回 0 条。
         * 这里**不做特殊处理**（不因为「反正都是 NULL」就直接返回空）——
         * 万一现场后来补了这三列，这里的逻辑要能自然跟上，
         * 而不是留一个写死的返回空。
         */
        if (query.getWithDevice() != null) {
            if (query.getWithDevice()) {
                wrapper.and(w -> w.isNotNull(UserLogEntity::getLogIP)
                        .or().isNotNull(UserLogEntity::getLogMAC)
                        .or().isNotNull(UserLogEntity::getLogOS));
            } else {
                wrapper.isNull(UserLogEntity::getLogIP)
                        .isNull(UserLogEntity::getLogMAC)
                        .isNull(UserLogEntity::getLogOS);
            }
        }

        LocalDateTime from = TimeParams.parse(query.getFrom(), "from");
        LocalDateTime to = TimeParams.parse(query.getTo(), "to");
        wrapper.ge(from != null, UserLogEntity::getLogTime, from);
        wrapper.le(to != null, UserLogEntity::getLogTime, to);

        applySort(wrapper, query.getSortBy(), query.getSortOrder());

        Page<UserLogEntity> page = userLogMapper.selectPage(
                Page.of(query.pageOrDefault(), query.pageSizeOrDefault()), wrapper);

        return PageResult.of(page.getRecords().stream().map(AccountLogService::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 按账号聚合（「登录频次」视图）。
     *
     * <p>由后端 GROUP BY 一次算好 —— 前端拿分页明细自己数只能得到当前页
     * （项目里反复强调过的一点）。
     */
    public PageResult<AccountLogStatView> listLogStats(String keyword,
                                                      int page, int pageSize) {
        String kw = trimToNull(keyword);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long offset = (long) (safePage - 1) * safeSize;

        long total = userLogMapper.countAccountStats(kw);
        List<AccountLogStatView> list = userLogMapper
                .selectAccountStats(kw, offset, safeSize).stream()
                .map(r -> new AccountLogStatView(
                        r.Account(),
                        r.cnt() == null ? 0L : r.cnt(),
                        DateTimes.format(r.firstTime()),
                        DateTimes.format(r.lastTime())))
                .toList();
        return PageResult.of(list, total, safePage, safeSize);
    }

    /**
     * 按天聚合（趋势图）。
     *
     * <p>⚠️ 早先这个方法返回的是 **mapper 的 {@code DayCountRow}** ——
     * 那属于数据访问层的类型，直接当接口出参会把 SQL 列名（{@code day} / {@code cnt}）
     * 泄漏成前端契约（而前端契约是 {@code date} / {@code count}）。
     * 现在映射成 {@link UserLogDailyRow}，与条件单/委托两个趋势接口的形状对齐。
     *
     * <p>无数据的日期由调用方按需补零（前端趋势图自己补）。
     */
    public List<UserLogDailyRow> daily(String from, String to) {
        List<UserLogDailyRow> out = new ArrayList<>();
        for (UserLogMapper.DayCountRow row : userLogMapper.selectDaily(
                TimeParams.parse(from, "from"), TimeParams.parse(to, "to"))) {
            out.add(new UserLogDailyRow(row.day(), row.cnt() == null ? 0L : row.cnt()));
        }
        return out;
    }

    /**
     * 排序白名单。
     *
     * <p>默认按 id 倒序（等价于时间倒序，且走主键，19 万行下 ~1ms）。
     * 用 LogTime 排序在无索引时也是全表扫，默认不用它。
     */
    private void applySort(LambdaQueryWrapper<UserLogEntity> wrapper,
                           String sortBy, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String field = trimToNull(sortBy);
        if (field == null || "id".equals(field)) {
            wrapper.orderBy(true, asc, UserLogEntity::getId);
        } else if ("LogTime".equals(field)) {
            wrapper.orderBy(true, asc, UserLogEntity::getLogTime);
        } else if ("Account".equals(field)) {
            wrapper.orderBy(true, asc, UserLogEntity::getAccount);
        } else {
            throw BizException.badRequest("不支持按 " + field + " 排序");
        }
        // 稳定次序：时间相同时用 id 兜底，避免翻页时重复或漏行
        wrapper.orderByDesc(UserLogEntity::getId);
    }

    private static UserLogView toView(UserLogEntity e) {
        return new UserLogView(
                e.getId(),
                e.getAccount(),
                DateTimes.format(e.getLogTime()),
                e.getLogIP(),
                e.getLogMAC(),
                e.getLogOS(),
                e.getInfo());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供排查：设备三列是否全为 NULL（页面上的说明依赖这个事实） */
    public boolean deviceColumnsAllNull() {
        return userLogMapper.selectCount(Wrappers.<UserLogEntity>lambdaQuery()
                .isNotNull(UserLogEntity::getLogIP)
                .or().isNotNull(UserLogEntity::getLogMAC)
                .or().isNotNull(UserLogEntity::getLogOS)) == 0;
    }
}
