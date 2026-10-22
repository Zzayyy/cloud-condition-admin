package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalListMeta;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalPage;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalQuery;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalStats;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalView;
import com.qianlong.cloud.admin.dto.ConditionalDtos.DailyCreatedRow;
import com.qianlong.cloud.admin.dto.ConditionalDtos.TypeCountRow;
import com.qianlong.cloud.admin.entity.ConditionalEntity;
import com.qianlong.cloud.admin.mapper.ConditionalMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 条件单查询 —— <b>只读 MySQL</b> 的 {@code T_Conditional} / {@code T_Conditional_his}
 * / {@code V_Conditional}。
 *
 * <h2>⭐ 2026-10-22：读路径从「Redis + MySQL 双源」改回「纯 MySQL」</h2>
 * 2026-10-21 曾把主数据源换成 Redis（当时 MySQL 三张表 0 行、页面全空），
 * 并做了双源合并。结果页面上出现 58 条、而库里只有 2 条 —— 看着像"凭空多出一堆"。
 *
 * <p>需求方与现场确认后明确：
 * <blockquote>
 * 「MySQL 和 Redis 应该是一致的（如果不一致，要不就是收盘后还未开盘）。
 * 你两个都读的话肯定会读重复，就方便性来说肯定是读 MySQL 的表方便
 * （Redis 数据都分散保存在各个账号对应的 {@code strategy-} 键中）。」
 * </blockquote>
 *
 * <p>所以：
 * <ul>
 *   <li><b>读</b>（本类）→ 只读 MySQL。两边一致时不会重复；</li>
 *   <li><b>写</b>（{@code ConditionalDispatchService} / {@code ConditionalTargetLookup}）
 *       → 仍需读 Redis 定位目标编号与数组下标。Redis 只服务写路径。</li>
 * </ul>
 *
 * <p><b>为什么不再保留"MySQL 挂了就降级显示 Redis"</b>：那个降级会让人以为
 * "表里就这么多"，把真实故障掩盖成一个看似正常的空列表。现在读失败直接报错。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConditionalService {

    /**
     * 内存里最多保留多少条条件单。
     *
     * <p>Redis 侧是「一次读全量 → 内存筛选 → 内存分页」，现场只有 60 条，代价可忽略。
     * 但如果哪天涨到百万级，这条上限会生效 —— 那时应该改成在 Redis 侧分页，
     * 而不是继续全量读进内存。**上限生效时 meta 会标注 truncated**，不静默截断。
     */
    private static final int MAX_ROWS = 20_000;

    private final ConditionalMapper conditionalMapper;

    /**
     * 读出全部条件单（<b>只读 MySQL</b>）。
     *
     * <p>现场约定：MySQL 与 Redis 应当一致（不一致只发生在"收盘后未开盘"这个窗口）。
     * 只读一边就不会重复 —— 两个都读再合并，本质是把同一批数据读两遍。
     *
     * @return 条件单列表；读失败时抛出让全局异常处理器给出明确错误，
     *         <b>不再</b>像双源时代那样"MySQL 挂了就只显示 Redis"的降级
     *         （那样会让人以为表里就这么多，掩盖真实故障）
     */
    private List<ConditionalView> loadAll() {
        List<ConditionalView> out = new ArrayList<>();
        for (ConditionalEntity e : conditionalMapper.selectPage(
                ConditionalSource.ALL.table(), null, null, null, 0, MAX_ROWS)) {
            out.add(toView(e));
        }
        return out;
    }

    /** 是否因为超过内存上限而被截断（截断了就如实标注，不静默丢数据） */
    private boolean truncated(int loaded) {
        if (loaded >= MAX_ROWS) {
            log.warn("条件单超过内存上限 {} 条，已截断（请改为数据库侧分页）", MAX_ROWS);
            return true;
        }
        return false;
    }

    /** 查询条件单（筛选 + 排序 + 分页） */
    public ConditionalPage listConditionals(ConditionalQuery query) {
        ConditionalSource source = ConditionalSource.of(query.getSource());
        String keyword = trimToNull(query.getKeyword());

        List<ConditionalView> all = loadAll();
        List<ConditionalView> rows = new ArrayList<>();
        for (ConditionalView v : all) {
            if (!source.matches(v.Mode(), v.Flag())) {
                continue;
            }
            if (!matchesKeyword(v, keyword)) {
                continue;
            }
            if (query.getType() != null && !query.getType().equals(v.Type())) {
                continue;
            }
            if (query.getMode() != null && !query.getMode().equals(v.Mode())) {
                continue;
            }
            rows.add(v);
        }
        rows.sort(Comparator
                .comparing((ConditionalView v) -> v.AddTime() == null ? "" : v.AddTime())
                .reversed()
                .thenComparing(v -> v.id() == null ? 0L : v.id(), Comparator.reverseOrder()));

        long total = rows.size();
        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long offset = (long) (page - 1) * pageSize;
        List<ConditionalView> pageRows = rows.stream()
                .skip(Math.min(offset, total))
                .limit(pageSize)
                .toList();

        ConditionalListMeta meta = new ConditionalListMeta(
                source.code(),
                source.label(),
                source.scopeText(),
                source.table(),
                total,
                truncated(all.size()));

        return new ConditionalPage(pageRows, total, page, pageSize, meta);
    }

    /** 单条详情（只查 MySQL） */
    public ConditionalView getConditional(Long id) {
        if (id == null) {
            return null;
        }
        for (ConditionalView v : loadAll()) {
            if (id.equals(v.id())) {
                return v;
            }
        }
        return null;
    }

    /**
     * 按天新增趋势（按 {@code AddTime} 分组）。
     *
     * <p>口径与列表同源（同一批数据），否则会出现"图上 30 张、列表里 0 张"
     * 这种没法解释的偏差。
     */
    public List<DailyCreatedRow> dailyCreated(String from, String to) {
        LocalDateTime f = TimeParams.parse(from, "from");
        LocalDateTime t = TimeParams.parse(to, "to");
        String fromText = f == null ? null : DateTimes.format(f);
        String toText = t == null ? null : DateTimes.format(t);
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (ConditionalView v : loadAll()) {
            String addTime = v.AddTime();
            if (addTime == null || addTime.length() < 10) {
                continue;
            }
            if (fromText != null && addTime.compareTo(fromText) < 0) {
                continue;
            }
            if (toText != null && addTime.compareTo(toText) > 0) {
                continue;
            }
            byDay.merge(addTime.substring(0, 10), 1L, Long::sum);
        }
        List<DailyCreatedRow> out = new ArrayList<>();
        byDay.forEach((day, cnt) -> out.add(new DailyCreatedRow(day, cnt)));
        out.sort(Comparator.comparing(DailyCreatedRow::date));
        return out;
    }

    /** 类型分布（全量口径） */
    public List<TypeCountRow> typeDistribution() {
        Map<Integer, Long> counts = new java.util.TreeMap<>();
        for (ConditionalView v : loadAll()) {
            if (v.Type() != null) {
                counts.merge(v.Type(), 1L, Long::sum);
            }
        }
        List<TypeCountRow> out = new ArrayList<>();
        counts.forEach((type, cnt) -> out.add(new TypeCountRow(type, typeLabel(type), cnt)));
        return out;
    }

    /**
     * 条件单汇总（全量口径，卡片用）。
     *
     * <p>按当前<b>筛选条件</b>统计全量，而不是统计当前页 —— 卡片上「运行中」
     * 「已触发」这类标签没有「本页」二字，给页内数字会被读成全量，且翻页就变。
     */
    public ConditionalStats stats(ConditionalQuery query) {
        ConditionalSource source = ConditionalSource.of(query.getSource());
        String keyword = trimToNull(query.getKeyword());
        long running = 0;
        long triggered = 0;
        long paused = 0;
        long totalRun = 0;
        long total = 0;
        for (ConditionalView v : loadAll()) {
            if (!source.matches(v.Mode(), v.Flag())) {
                continue;
            }
            if (!matchesKeyword(v, keyword)) {
                continue;
            }
            if (query.getType() != null && !query.getType().equals(v.Type())) {
                continue;
            }
            if (query.getMode() != null && !query.getMode().equals(v.Mode())) {
                continue;
            }
            total++;
            if (v.Mode() != null && v.Mode() == 1) {
                running++;
            }
            if (v.Status() != null && v.Status() == 1) {
                triggered++;
            }
            if (v.Flag() != null && v.Flag() == 1) {
                paused++;
            }
            if (v.RunCount() != null) {
                totalRun += v.RunCount();
            }
        }
        return new ConditionalStats(running, triggered, paused, totalRun, total);
    }

    /** 关键字匹配（账号 / 单号 / 名称 / 描述） */
    private static boolean matchesKeyword(ConditionalView v, String keyword) {
        if (keyword == null) {
            return true;
        }
        String k = keyword.toLowerCase();
        return contains(v.Account(), k) || contains(v.UserAccount(), k)
                || contains(v.Name(), k) || contains(v.Description(), k)
                || (v.id() != null && String.valueOf(v.id()).contains(k));
    }

    private static boolean contains(String value, String lowerKeyword) {
        return value != null && value.toLowerCase().contains(lowerKeyword);
    }

    /* ================================ 映射 ================================ */

    /** MySQL 行 → 前端契约形状（标 {@code source=db}） */
    private static ConditionalView toView(ConditionalEntity e) {
        return new ConditionalView(
                e.getId(), e.getAccount(), e.getUserAccount(), e.getStrategy(),
                DateTimes.format(e.getAddTime()), DateTimes.format(e.getRunTime()),
                DateTimes.format(e.getCloseTime()), e.getRunCount(),
                DateTimes.format(e.getBeginTime()), DateTimes.format(e.getEndTime()),
                e.getStatus(), e.getFlag(), e.getDescription(), e.getValidity(),
                DateTimes.format(e.getModifyTime()), DateTimes.format(e.getTriggeredTime()),
                e.getReason(), e.getMode(), e.getType(), e.getName());
    }

    /** 与前端 {@code CONDITIONAL_TYPES} 一致的标签 */
    private static String typeLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 0 -> "保留";
            case 1 -> "开仓条件单";
            case 2 -> "平仓条件单";
            case 3 -> "按合约止盈止损";
            case 4 -> "按标的止盈止损";
            default -> "类型 " + type;
        };
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
