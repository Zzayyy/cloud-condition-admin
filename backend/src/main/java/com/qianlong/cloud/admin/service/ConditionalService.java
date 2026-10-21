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
 * 条件单查询 —— <b>Redis 实时态为主 + MySQL 归档为辅</b>。
 *
 * <h2>⚠️ 为什么 2026-10-21 改成读 Redis</h2>
 * 现场实况（当日复核）：
 * <pre>
 *   MySQL  T_Conditional / T_Conditional_his / V_Conditional : 0 行
 *   Redis  strategy-* 键（136 db1 四个键 + 137 db1 两个键）  : 60 条真实条件单
 * </pre>
 * 只读 MySQL 的话页面永远是空的 —— 那不是"没有条件单"，是"没读对地方"。
 * 所以主数据源换成 Redis，MySQL 退为归档补充。
 *
 * <p><b>但"表为空"这个信号不能被掩盖</b>（本项目一贯的口径）：
 * 所以每行都带 {@code source}（redis / db），meta 里也如实给出两边的条数。
 * 运维能一眼看出"这些单只在实时态存在、还没落库"。
 *
 * <h2>合并规则</h2>
 * <ol>
 *   <li>同一个单号两边都有 → <b>以 Redis 为准</b>（实时态），标 {@code source=redis}；</li>
 *   <li>只在 Redis 有 → 照常展示，标 {@code source=redis}；</li>
 *   <li>只在 MySQL 有 → 照常展示，标 {@code source=db}。</li>
 * </ol>
 *
 * <h2>⚠️ 关于"读 Redis 会不会让页面读数失去意义"</h2>
 * 当初把 Redis 合并移出去，是因为<b>那时候只有读、没有写</b>：
 * 空表 + 外部数据 = 用户以为"表里有 58 条"，掩盖了"没落库"这个问题。
 * 现在有了写操作（状态变更），Redis 侧就是<b>正在运行的那份</b>，
 * 页面读它才是对的；而"有没有落库"这件事由 {@code source} 字段如实呈现。
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
    private final RedisConditionalRepository redisRepository;

    /**
     * 合并两个数据源。
     *
     * <p>Redis 优先（实时态），MySQL 补充（归档里可能有 Redis 已清掉的）。
     * 同一单号两边都有时以 Redis 为准，但计数照实统计。
     */
    private Merge merge() {
        List<ConditionalView> redis = redisRepository.findAll();
        List<ConditionalView> out = new ArrayList<>();
        java.util.Set<Long> redisIds = new java.util.HashSet<>();
        for (ConditionalView v : redis) {
            redisIds.add(v.id());
            out.add(v);
        }
        int dbOnly = 0;
        try {
            for (ConditionalEntity e : conditionalMapper.selectPage(
                    ConditionalSource.ALL.table(), null, null, null, 0, MAX_ROWS)) {
                if (redisIds.contains(e.getId())) {
                    continue;
                }
                dbOnly++;
                out.add(toView(e));
            }
        } catch (Exception ex) {
            // MySQL 读不到不影响 Redis 侧的展示 —— 但要如实说明，不能假装没有
            log.warn("读取 MySQL 条件单归档失败，页面只呈现 Redis 侧：{}", ex.getMessage());
        }
        boolean truncated = redis.size() >= MAX_ROWS;
        return new Merge(out, redis.size(), dbOnly, truncated);
    }

    /** 合并结果 */
    private record Merge(List<ConditionalView> views, int redisCount, int dbOnlyCount, boolean truncated) {
    }

    /** 查询条件单（筛选 + 排序 + 分页） */
    public ConditionalPage listConditionals(ConditionalQuery query) {
        ConditionalSource source = ConditionalSource.of(query.getSource());
        String keyword = trimToNull(query.getKeyword());

        Merge merge = merge();
        List<ConditionalView> rows = new ArrayList<>();
        for (ConditionalView v : merge.views()) {
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
                total,
                merge.redisCount(),
                merge.dbOnlyCount(),
                merge.truncated());

        return new ConditionalPage(pageRows, total, page, pageSize, meta);
    }

    /** 单条详情（Redis 优先，MySQL 兜底） */
    public ConditionalView getConditional(Long id) {
        if (id == null) {
            return null;
        }
        for (ConditionalView v : merge().views()) {
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
        for (ConditionalView v : merge().views()) {
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
        for (ConditionalView v : merge().views()) {
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
        for (ConditionalView v : merge().views()) {
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
                e.getReason(), e.getMode(), e.getType(), e.getName(),
                "db", null, null, 1);
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
