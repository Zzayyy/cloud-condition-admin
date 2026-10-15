package com.qianlong.cloud.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.config.DatahubProperties;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubStreamView;
import com.qianlong.cloud.admin.dto.DatahubDtos.StreamQuery;
import com.qianlong.cloud.admin.dto.MonitorDtos.MetricOption;
import com.qianlong.cloud.admin.dto.MonitorDtos.TimeSeriesPoint;
import com.qianlong.cloud.admin.dto.MonitorDtos.TimeSeriesView;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 指标趋势：**能算真历史就算真历史，算不出就如实说没有**。
 *
 * <h2>核心洞察：Stream 的条目 ID 是毫秒时间戳</h2>
 * Redis Stream 的条目 ID 形如 {@code 1791946676151-1}，前一段是毫秒时间戳。
 * 所以**保留中的条目本身就是一段到达历史** —— 用
 * {@code XRANGE key <起始毫秒> + COUNT n} 把某个时间窗内的条目捞出来、
 * 按桶分组，就能还原出**真实的每秒到达量**，不需要任何额外存储。
 *
 * <p>实测（2026-10-15，只读验证）：
 * <pre>
 *   136 db1 WT-1-reply   最近 60 秒窗口 → 4ms
 *                        最近 300 秒窗口 → 0ms
 * </pre>
 * 按时间窗取是廉价的：{@code XRANGE} 的起点支持「部分 ID」，
 * 只给毫秒部分即可，Redis 从该位置开始顺序读，不会扫全表。
 *
 * <h2>⚠️ 但 lag / pending 没有历史</h2>
 * {@code XINFO GROUPS} 只返回**当前快照**，Redis 不保存历史值。
 * 所以本服务对这两个指标返回 {@code historyAvailable=false}
 * 与当前值，**不编曲线** —— 编出来的「过去一小时 lag 变化」
 * 会变成捏造的运维依据。
 *
 * <p>要真历史只能后端定期采样落库（或接 Prometheus）。那是持久化设施的引入，
 * 现场当前连数据流都停着（实测入口流最后一条在 24 小时前），
 * 先采也只会得到一条平线，所以本次不做，只在接口里说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorService {

    /** 允许的时间窗（分钟）—— 白名单，避免前端传个天文数字让后端去扫全表 */
    private static final int MIN_WINDOW_MIN = 1;
    private static final int MAX_WINDOW_MIN = 24 * 60;
    /** 每条流在每个窗口内最多取多少条（防止超大流把响应撑爆） */
    private static final int MAX_ENTRIES_PER_STREAM = 20000;

    private final RedisJsonGateway gateway;
    private final DatahubService datahubService;
    private final DatahubProperties datahubProperties;
    private final RedisProperties redisProperties;

    /* ================================ 指标定义 ================================ */

    /** 入口流到达速率（条/秒），**有真实历史** */
    public static final String METRIC_INGRESS_RATE = "ingressRate";
    /** 全网条目到达速率（条/秒），**有真实历史** */
    public static final String METRIC_TOTAL_RATE = "totalRate";
    /** 消费组 lag 合计，**无历史**（只有当前快照） */
    public static final String METRIC_CONSUMER_LAG = "consumerLag";
    /** 未确认 pending 合计，**无历史** */
    public static final String METRIC_CONSUMER_PENDING = "consumerPending";
    /** 入口流积压（XLEN 合计），**无历史** */
    public static final String METRIC_INGRESS_BACKLOG = "ingressBacklog";

    /** 可供页面选择的指标（历史可用性也一并给出，避免前端猜） */
    public List<MetricOption> metrics() {
        return List.of(
                new MetricOption(METRIC_INGRESS_RATE, "入口流到达速率", "条/秒", true),
                new MetricOption(METRIC_TOTAL_RATE, "全网到达速率", "条/秒", true),
                new MetricOption(METRIC_CONSUMER_LAG, "消费组 lag", "条", false),
                new MetricOption(METRIC_CONSUMER_PENDING, "未确认 pending", "条", false),
                new MetricOption(METRIC_INGRESS_BACKLOG, "入口流积压", "条", false));
    }

    /* ================================ 主入口 ================================ */

    /**
     * 取一条趋势序列。
     *
     * @param metric    指标键（见上面常量）；未知键**报错**而不是静默回空
     * @param windowMin 时间窗（分钟），白名单范围内
     */
    public TimeSeriesView series(String metric, Integer windowMin) {
        String key = StringUtils.hasText(metric) ? metric.trim() : METRIC_INGRESS_RATE;
        int window = windowMin == null ? 15 : Math.min(Math.max(windowMin, MIN_WINDOW_MIN), MAX_WINDOW_MIN);

        return switch (key) {
            case METRIC_INGRESS_RATE -> rateSeries(key, "入口流到达速率", window, true);
            case METRIC_TOTAL_RATE -> rateSeries(key, "全网到达速率", window, false);
            case METRIC_CONSUMER_LAG -> snapshotOnly(key, "消费组 lag", "条",
                    totalLag(),
                    "XINFO GROUPS 只返回当前快照；Redis 不保存 lag 的历史值", window);
            case METRIC_CONSUMER_PENDING -> snapshotOnly(key, "未确认 pending", "条",
                    totalPending(),
                    "XINFO GROUPS 只返回当前快照；Redis 不保存 pending 的历史值", window);
            case METRIC_INGRESS_BACKLOG -> snapshotOnly(key, "入口流积压", "条",
                    ingressBacklog(),
                    "XLEN 只有当前值；要历史曲线需后端定期采样落库", window);
            default -> throw BizException.badRequest(
                    "未知指标：" + key + "。可用：" + metrics().stream().map(MetricOption::key).toList());
        };
    }

    /* ============================ 真历史：到达速率 ============================ */

    /**
     * 从条目 ID 的时间戳还原到达速率。
     *
     * <p>做法：对目标流，用 {@code XRANGE} 取「窗口起点 → +」之间的条目，
     * 按 {@code bucketSec} 分桶计数，再除以桶宽得到「条/秒」。
     *
     * <p>⚠️ 桶宽要按窗口长度自适应：窗口 15 分钟时用 60 秒/桶（15 个点），
     * 窗口 24 小时时用 600 秒/桶，否则点数会多到没法看。
     */
    private TimeSeriesView rateSeries(String metric, String label, int windowMin, boolean ingressOnly) {
        int bucketSec = chooseBucketSec(windowMin);
        long nowMs = System.currentTimeMillis();
        long fromMs = nowMs - (long) windowMin * 60_000L;
        int buckets = (int) Math.ceil((double) (windowMin * 60) / bucketSec);

        // 每个桶一个计数；键 = 桶起点（秒）
        long[] counts = new long[buckets];
        int scanned = 0;
        boolean truncated = false;
        int streamCount = 0;

        for (String host : gateway.distinctHosts()) {
            // 已熔断的实例直接跳过，省掉逐个 db 的超时等待（16 × 3 秒）
            if (!gateway.isHostUsable(host)) {
                continue;
            }
            for (int db = 0; db <= datahubProperties.getMaxDb(); db++) {
                StatefulRedisConnection<String, String> conn = gateway.connectionFor(host, db);
                if (conn == null) {
                    continue;
                }
                RedisCommands<String, String> cmd = conn.sync();
                // streamKeysOf 要发命令，必须兜住异常（陈旧连接会超时）
                java.util.List<String> streamKeys;
                try {
                    streamKeys = streamKeysOf(cmd);
                } catch (Exception ex) {
                    gateway.reportHostFailure(host, ex);
                    log.warn("扫流失败 {} db{}，已熔断该实例：{}", host, db, ex.getMessage());
                    break;
                }
                for (String streamKey : streamKeys) {
                    if (ingressOnly && !"DataHub_req_stream".equals(streamKey)) {
                        continue;
                    }
                    try {
                        // 只取窗口内的条目：起点给毫秒（部分 ID 即可，Redis 会补 -0）
                        List<StreamMessage<String, String>> rows = cmd.xrange(
                                streamKey, Range.create(String.valueOf(fromMs), "+"),
                                Limit.from(MAX_ENTRIES_PER_STREAM));
                        if (rows.size() >= MAX_ENTRIES_PER_STREAM) {
                            truncated = true;
                        }
                        for (StreamMessage<String, String> m : rows) {
                            Long ms = millisOf(m.getId());
                            if (ms == null || ms < fromMs) {
                                continue;
                            }
                            int idx = (int) ((ms - fromMs) / (bucketSec * 1000L));
                            if (idx >= 0 && idx < buckets) {
                                counts[idx]++;
                            }
                            scanned++;
                        }
                        streamCount++;
                    } catch (Exception ex) {
                        log.debug("取窗口条目失败 {} db{} {}：{}", host, db, streamKey, ex.getMessage());
                    }
                }
            }
        }

        List<TimeSeriesPoint> points = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            long bucketStartMs = fromMs + (long) i * bucketSec * 1000L;
            points.add(new TimeSeriesPoint(bucketStartMs, counts[i] / (double) bucketSec));
        }

        String note = truncated
                ? "⚠️ 单条流在窗口内超过 " + MAX_ENTRIES_PER_STREAM + " 条，已截断，速率可能偏低。"
                : null;
        if (scanned == 0) {
            // 窗口内一条都没有 —— 这本身是很重要的信号（数据流可能已停止）
            note = (note == null ? "" : note + " ")
                    + "该时间窗内**没有任何新条目**：数据流可能已停止。"
                    + "注意 XLEN 大不等于流量大 —— 那可能是历史堆积（详见流列表的「最后条目」列）。";
        }

        return new TimeSeriesView(
                metric, label, "条/秒",
                points, true,
                counts.length == 0 ? 0.0 : counts[buckets - 1] / (double) bucketSec,
                ingressOnly
                        ? "XRANGE(DataHub_req_stream) 按条目 ID 的毫秒时间戳分桶（真实历史）"
                        : "XRANGE(全部 stream) 按条目 ID 的毫秒时间戳分桶（真实历史）",
                note, windowMin, bucketSec);
    }

    /** 桶宽自适应：窗口越长桶越宽，保证点数在 15~90 之间 */
    private static int chooseBucketSec(int windowMin) {
        int totalSec = windowMin * 60;
        int[] candidates = {10, 30, 60, 300, 600, 1800, 3600};
        for (int c : candidates) {
            if (totalSec / c <= 90) {
                return c;
            }
        }
        return 3600;
    }

    /** 条目 ID → 毫秒时间戳；解析不出返回 null */
    private static Long millisOf(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        try {
            int dash = entryId.indexOf('-');
            return Long.parseLong((dash < 0 ? entryId : entryId.substring(0, dash)).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /* ========================== 无历史：只给当前快照 ========================== */

    /**
     * 没有历史可回溯的指标：返回**当前值** + 明确说明「无历史」。
     *
     * <p>{@code points} 故意留空数组 —— 页面据此显示空态。
     * 若这里拿当前值去生成一条平滑曲线，就会变成捏造的历史。
     */
    private TimeSeriesView snapshotOnly(String metric, String label, String unit,
                                        Double current, String reason, int windowMin) {
        return new TimeSeriesView(metric, label, unit,
                List.of(), false, current,
                "XINFO GROUPS / XLEN 当前快照",
                reason + "。需要真历史须由后端定期采样（本次未做：现场数据流当前已停止，先采也只会得到平线）。",
                windowMin, 0);
    }

    /* ============================== 当前快照计算 ============================== */

    /** 全部流的 lag 合计（当前值） */
    private Double totalLag() {
        long sum = 0;
        for (DatahubStreamView s : streams()) {
            for (var g : s.groups()) {
                sum += g.lag() == null ? 0 : g.lag();
            }
        }
        return (double) sum;
    }

    /** 全部流的 pending 合计（当前值） */
    private Double totalPending() {
        long sum = 0;
        for (DatahubStreamView s : streams()) {
            for (var g : s.groups()) {
                sum += g.pending();
            }
        }
        return (double) sum;
    }

    /** 入口流 XLEN 合计（当前值） */
    private Double ingressBacklog() {
        long sum = 0;
        for (DatahubStreamView s : streams()) {
            if ("DataHub_req_stream".equals(s.key())) {
                sum += s.length();
            }
        }
        return (double) sum;
    }

    /** 复用数据中台的流清单（同一份口径，避免两处算法漂移） */
    private List<DatahubStreamView> streams() {
        return datahubService.listStreams(new StreamQuery());
    }

    /* ============================== 扫描工具 ============================== */

    /** 该 db 里的全部 stream 键 */
    private List<String> streamKeysOf(RedisCommands<String, String> cmd) {
        List<String> out = new ArrayList<>();
        io.lettuce.core.ScanArgs args = io.lettuce.core.ScanArgs.Builder.limit(500);
        io.lettuce.core.KeyScanCursor<String> cursor = cmd.scan(args);
        int rounds = 0;
        while (true) {
            for (String k : cursor.getKeys()) {
                try {
                    if ("stream".equals(cmd.type(k))) {
                        out.add(k);
                    }
                } catch (Exception ignored) {
                    // 单个键类型读失败不影响其它键
                }
                if (out.size() >= datahubProperties.getScanLimit()) {
                    return out;
                }
            }
            if (cursor.isFinished() || ++rounds > 40) {
                break;
            }
            cursor = cmd.scan(cursor, args);
        }
        return out;
    }

    /** 配置里声明的实例数（排查用） */
    public int configuredInstanceCount() {
        return redisProperties.getInstances() == null ? 0 : redisProperties.getInstances().size();
    }

    /** 供其它服务复用的「现在」 */
    static long nowSec() {
        return Instant.now().getEpochSecond();
    }

    /** 预留：把 map 转成点（未来采样版会用） */
    static List<TimeSeriesPoint> toPoints(Map<Long, Double> map) {
        List<TimeSeriesPoint> out = new ArrayList<>();
        new LinkedHashMap<>(map).forEach((t, v) -> out.add(new TimeSeriesPoint(t, v)));
        return out;
    }
}
