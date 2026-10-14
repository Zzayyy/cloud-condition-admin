package com.qianlong.cloud.admin.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.DailyLogRow;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogMeta;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogPage;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogQuery;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogView;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyStatRow;
import com.qianlong.cloud.admin.entity.StrategyLogEntity;
import com.qianlong.cloud.admin.mapper.StrategyLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 策略日志查询 —— 只读 {@code T_StrategyLog}（现场 17430 行）。
 *
 * <p><b>只读这一张表</b>，不掺任何其它数据源。
 *
 * <h2>⚠️ 现场三列为空，本模块的核心职责是「如实呈现」而不是「让页面好看」</h2>
 * 实测（2026-10-14）：
 * <ul>
 *   <li><b>{@code Status} 17430 行全部为 NULL</b> ——
 *       前端页面上的状态下拉（触发成功 / 触发失败 / …）来自
 *       {@code mock/strategy.ts} 的 {@code LOG_STATUS}，那是**编的**。
 *       本模块通过 {@code /strategy/logs/statuses} 返回真实去重结果（空数组），
 *       {@code meta.statusColumnEmpty=true} 让页面能说明「该列现场为空」。</li>
 *   <li><b>{@code FromIP} / {@code FromMac} 全部为 NULL</b> ——
 *       与 {@code T_UserLog} 的设备三列同类。所以「按来源 IP 筛」必然 0 条。</li>
 *   <li>{@code OccurTIme} 是 <b>{@code date}</b>（无时分秒），
 *       且该列存过非法空串，**不能**用 {@code <> ''} 比较（会报 MySQL 1525）。</li>
 * </ul>
 *
 * <p>这些事实必须原样传给前端，而不是「补个默认值让界面好看」——
 * 补了之后，运维看到的下拉框里有 7 个选项、每个都查不出数据，
 * 会花时间去怀疑是查询写错了，而不是「这一列根本没数据」。
 *
 * <h2>性能</h2>
 * 表上只有主键索引，不能加（不改既有表结构）。
 * 实测：主键倒序分页 ~1ms、按 Strategy 过滤 ~7ms、按天聚合 ~6ms。
 * 1.7 万行下都可接受；涨到百万级需要与现场协商加索引。
 */
@Service
@RequiredArgsConstructor
public class StrategyLogService {

    /**
     * 时间线单次返回上限。
     *
     * <p>某个条件单可能挂了很多条日志（现场 `Strategy` 分布里最多的是 252 条）。
     * 留 500 的余量足够看完整轨迹，同时防止极端情况把内存打爆。
     */
    private static final int TIMELINE_LIMIT = 500;

    private final StrategyLogMapper strategyLogMapper;

    /** 分页查策略日志 */
    public StrategyLogPage listLogs(StrategyLogQuery query) {
        String keyword = trimToNull(query.getKeyword());
        String status = trimToNull(query.getStatus());
        String fromIp = trimToNull(query.getFromIp());
        LocalDate from = parseDate(query.getFrom(), "from");
        LocalDate to = parseDate(query.getTo(), "to");

        long total = strategyLogMapper.countPage(keyword, status, fromIp, from, to);

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long offset = (long) (page - 1) * pageSize;

        List<StrategyLogView> rows = strategyLogMapper.selectPage(
                        keyword, status, fromIp, from, to, offset, pageSize)
                .stream().map(StrategyLogService::toView).toList();

        return new StrategyLogPage(rows, total, page, pageSize, currentMeta(total));
    }

    /**
     * {@code Status} 列的真实去重取值。
     *
     * <p>前端**应当**用它来渲染下拉，而不是从 {@code mock/strategy.ts} 取
     * {@code LOG_STATUS}（那 7 个值现场一个都不存在）。
     * 现在返回空数组 —— 这就是事实。
     */
    public List<String> statuses() {
        return strategyLogMapper.selectDistinctStatuses();
    }

    /** 按天日志量（趋势图） */
    public List<DailyLogRow> dailyLogs(String from, String to) {
        LocalDate f = parseDate(from, "from");
        LocalDate t = parseDate(to, "to");
        List<DailyLogRow> out = new ArrayList<>();
        for (StrategyLogMapper.DayCountRow row : strategyLogMapper.selectDaily(f, t)) {
            if (row.day() == null) {
                // 非法空串日期经 GROUP BY 会落到 NULL 分组；跳过而不是编一个日期
                continue;
            }
            out.add(new DailyLogRow(DateTimes.DATE.format(row.day()),
                    row.cnt() == null ? 0L : row.cnt()));
        }
        return out;
    }

    /** 按条件单聚合（「按策略单查看生命周期」视图） */
    public PageResult<StrategyStatRow> strategyStats(String keyword, int page, int pageSize) {
        String kw = trimToNull(keyword);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        long offset = (long) (safePage - 1) * safeSize;

        long total = strategyLogMapper.countStrategyStats(kw);
        List<StrategyStatRow> list = strategyLogMapper
                .selectStrategyStats(kw, offset, safeSize).stream()
                .map(r -> new StrategyStatRow(
                        r.strategy(),
                        r.cnt() == null ? 0L : r.cnt(),
                        DateTimes.formatLocalDate(r.firstDay()),
                        DateTimes.formatLocalDate(r.lastDay())))
                .toList();
        return PageResult.of(list, total, safePage, safeSize);
    }

    /**
     * 某个条件单的完整日志轨迹（详情抽屉的时间线）。
     *
     * <p>按 {@code OccurTIme} 升序 —— 轨迹要按时间顺序看才有意义，
     * 与列表的「最新在前」是相反的方向（列表是为了先看最近的）。
     */
    public List<StrategyLogView> timeline(Long strategy) {
        if (strategy == null) {
            return List.of();
        }
        return strategyLogMapper.selectTimeline(strategy, TIMELINE_LIMIT).stream()
                .map(StrategyLogService::toView)
                .toList();
    }

    /* ================================ 工具 ================================ */

    /**
     * 列表元信息：把「哪几列为空」这件事查准了传给前端。
     *
     * <p>不是硬编码 {@code true}：真的去查一次，这样现场补了数据之后
     * 页面会自动变成正常显示，而不需要改代码。
     */
    private StrategyLogMeta currentMeta(long total) {
        List<String> statuses = strategyLogMapper.selectDistinctStatuses();
        boolean statusEmpty = statuses.isEmpty();
        // 三列为空这一事实只需要查一次即可判断 FromIP/FromMac
        boolean deviceEmpty = strategyLogMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<StrategyLogEntity>lambdaQuery()
                        .isNotNull(StrategyLogEntity::getFromIP)
                        .or().isNotNull(StrategyLogEntity::getFromMac)) == 0;
        return new StrategyLogMeta(total, statuses, statusEmpty, deviceEmpty);
    }

    private static StrategyLogView toView(StrategyLogEntity e) {
        return new StrategyLogView(
                e.getId(),
                e.getStrategy(),
                e.getStatus(),
                e.getInfo(),
                // date 列：只给日期，不补 00:00:00
                DateTimes.formatLocalDate(e.getOccurTIme()),
                e.getFromIP(),
                e.getFromMac(),
                e.getFromAccount());
    }

    /**
     * 解析日期范围。
     *
     * <p>{@code OccurTIme} 是 {@code date}，所以按日期解析。
     * 传了带时分秒的字符串也能接受（取日期部分），
     * 但**不会**把它拼进 SQL 去和 date 列比较 —— 那会引入隐式转换。
     */
    private static LocalDate parseDate(String raw, String name) {
        var dt = TimeParams.parse(raw, name);
        return dt == null ? null : dt.toLocalDate();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 供 controller 用：条件单编号缺失时 */
    public static BizException badStrategyId() {
        return BizException.badRequest("必须提供条件单编号 strategy");
    }
}
