package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.config.DatahubProperties;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubChannelView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubEntryPage;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubNodeListMeta;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubNodePage;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubNodeView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubStreamEntryView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubStreamView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubTraceView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DbStat;
import com.qianlong.cloud.admin.dto.DatahubDtos.StreamGroupView;
import com.qianlong.cloud.admin.dto.DatahubDtos.StreamQuery;
import com.qianlong.cloud.admin.dto.DatahubDtos.TraceHit;
import com.qianlong.cloud.admin.service.DatahubHeartbeatMonitor.State;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据中台：实例（节点）清单、Pub/Sub 频道、Stream 规模与条目。
 *
 * <h2>⚠️ 本模块最容易被误读的三件事（都照现场实测处理）</h2>
 *
 * <h3>1. 「节点」是 Redis 实例，不是中台实例</h3>
 * 中台**没有节点清单键**（委托服务器有 {@code traderserver}，中台没有对应的东西）。
 * 所以本模块的一行 = 一台 Redis 实例；其「在线」= 该实例上是否收到过中台心跳。
 * <b>这不等于「这台机器上跑着中台」</b>：现场中台进程在 137 上，连的却是 136 的 Redis。
 * 页面必须把这句说明摆出来，否则「2 个节点」会被当成「2 个中台」。
 *
 * <h3>2. 键数 ≠ 条目数</h3>
 * 现场 137 db0：{@code DBSIZE = 117} 个键，但单个 {@code DataHub_req_stream}
 * 就有 <b>1400 万</b>条目。混着看会得出完全错误的容量判断。
 * 所以本模块把 {@code keys} 与 {@code length} 做成**不同字段**，
 * 且 {@code keys} 用 O(1) 的 DBSIZE、{@code length} 逐键 XLEN（受上限约束）。
 *
 * <h3>3. {@code unique_id} 各 db 独立，且可能不存在</h3>
 * 它是 string 键而非全局计数器：现场 136 db0=217 / db1=56、137 db1=4，
 * 而 137 db0 与 db2 **没有这个键** → 返回 {@code null}。
 * 不能用 0 表示「没有」——0 是一个真实水位。
 *
 * <h2>性能与安全</h2>
 * 全部只读（{@code DBSIZE / SCAN / XLEN / XINFO GROUPS / PUBSUB / XRANGE}）。
 * stream 条目**只在尾部窗口内取**：1400 万条的流一旦全量拉取，
 * 既会拖垮 Redis 也会撑爆接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatahubService {

    /** 取条目时的默认窗口大小：只在最近这么多条里查 */
    private static final int DEFAULT_WINDOW = 200;
    /** 窗口硬上限：前端传再大也不超过它 */
    private static final int MAX_WINDOW = 5000;
    /** 单次 SCAN 的游标批次 */
    private static final int SCAN_COUNT = 500;
    /** SCAN 轮数上限（游标异常时兜底，避免接口被挂住） */
    private static final int MAX_SCAN_ROUNDS = 40;

    private final RedisJsonGateway gateway;
    private final RedisAdminService redisAdminService;
    private final DatahubHeartbeatMonitor heartbeatMonitor;
    private final DatahubProperties datahubProperties;
    private final RedisProperties redisProperties;

    /* ================================ 节点（实例） ================================ */

    /**
     * 实例清单：一行一台 Redis 实例，附各 db 统计与心跳状态。
     */
    public DatahubNodePage listNodes(Integer page, Integer pageSize) {
        List<String> hosts = gateway.distinctHosts();
        List<DatahubNodeView> rows = new ArrayList<>();
        int online = 0;
        int offline = 0;
        int unknown = 0;
        int observed = 0;

        for (String host : hosts) {
            DatahubNodeView row = buildNode(host);
            if (Boolean.TRUE.equals(row.online())) {
                online++;
            } else if (Boolean.FALSE.equals(row.online())) {
                offline++;
            } else {
                unknown++;
            }
            if (row.subscribed()) {
                observed++;
            }
            rows.add(row);
        }

        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? 50 : Math.min(pageSize, 200);
        int from = Math.min((p - 1) * ps, rows.size());
        int to = Math.min(from + ps, rows.size());

        DatahubNodeListMeta meta = new DatahubNodeListMeta(
                rows.size(),
                online,
                offline,
                unknown,
                heartbeatMonitor.isEnabled(),
                heartbeatMonitor.channels(),
                datahubProperties.getOfflineAfterSec(),
                observed,
                gateway.availableInstanceNames(),
                List.copyOf(gateway.unavailableInstances().keySet()),
                "一行 = 一台 Redis 实例（中台在 Redis 侧没有节点清单键）。"
                        + "「在线」指该实例上近期收到过中台心跳，"
                        + "不等于「这台机器上跑着中台」——现场中台进程在 137，连的是 136 的 Redis。"
                        + "心跳报文不含身份，故无法归属到具体某台中台。");

        return new DatahubNodePage(List.copyOf(rows.subList(from, to)),
                rows.size(), p, ps, meta);
    }

    /** 构造一台实例的行 */
    private DatahubNodeView buildNode(String host) {
        int port = gateway.portOfHost(host);
        List<DbStat> dbs = new ArrayList<>();
        long keysTotal = 0;
        long entryTotal = 0;
        int streamTotal = 0;

        for (int db = 0; db <= datahubProperties.getMaxDb(); db++) {
            StatefulRedisConnection<String, String> conn = gateway.connectionFor(host, db);
            if (conn == null) {
                continue;
            }
            RedisCommands<String, String> cmd = conn.sync();
            long keys;
            try {
                keys = cmd.dbsize();
            } catch (Exception ex) {
                log.warn("DBSIZE 失败 {} db{}：{}", host, db, ex.getMessage());
                continue;
            }
            // 键数为 0 的 db 直接跳过：既不显示，也不必去扫（现场很多空 db）
            if (keys == 0) {
                continue;
            }
            Long uniqueId = safeGetLong(cmd, "unique_id");
            StreamStat stat = scanStreams(cmd);
            dbs.add(new DbStat(db, keys, uniqueId, stat.entryTotal(), stat.streamTotal()));
            keysTotal += keys;
            entryTotal += stat.entryTotal();
            streamTotal += stat.streamTotal();
        }

        State state = heartbeatMonitor.stateOf(host);
        Boolean online = switch (state) {
            case ONLINE -> Boolean.TRUE;
            case OFFLINE -> Boolean.FALSE;
            // ⚠️ 未知必须是 null，不能压成 false（那会把「没在看」说成「离线」）
            case UNKNOWN -> null;
        };

        int channels = safeChannelCount(host);

        return new DatahubNodeView(
                host,
                host,
                String.valueOf(port),
                online,
                state.name(),
                heartbeatMonitor.ageSecOf(host),
                heartbeatMonitor.beatCountOf(host),
                heartbeatMonitor.isSubscribed(host),
                heartbeatMonitor.errorOf(host),
                List.copyOf(dbs),
                keysTotal,
                entryTotal,
                streamTotal,
                channels);
    }

    /* ================================ 频道 ================================ */

    /**
     * 全部实例的 Pub/Sub 频道（跨实例汇总）。
     *
     * <p>频道是**实例级**的、不按 db 隔离，所以不带 db 维度。
     * 现场 136 有 14 个、137 一个都没有 —— 这个差异本身就是要看见的信息。
     *
     * <p>⚠️ <b>必须扣除我们自己那份订阅（观测者效应）。</b>
     * 本模块为了让心跳判定可用，会在每台实例上常驻订阅 {@code datahub_beat_1}。
     * 而 {@code PUBSUB CHANNELS} 只列「有订阅者的频道」，于是：
     * <pre>
     *   现场 137 原本 0 个频道 → 我们订阅后变成 1 个（完全是我们造出来的）
     *   136 的 datahub_beat_1 订阅者原本 1 → 变成 2
     * </pre>
     * 不修正的话，看这个页面（以及 Redis 管理页）的人会以为现场真有这些频道/订阅者。
     *
     * <p>处理方式分两种，都**不静默**：
     * <ul>
     *   <li><b>只因为我们才存在的频道</b>（扣除后订阅者为 0）——
     *       不计入列表，但登记到 {@link DatahubNodeListMeta#selfOnlyChannels()}，
     *       让人知道「我们在这个实例上听着，但现场并没有这个频道」；</li>
     *   <li><b>本来就有、我们只是多了一份订阅的频道</b> —— 保留在列表里，
     *       订阅者数扣除我们那一份，并标 {@code selfSubscribed=true}。</li>
     * </ul>
     */
    public ChannelScan listChannels() {
        List<DatahubChannelView> out = new ArrayList<>();
        List<String> selfOnly = new ArrayList<>();
        java.util.Set<String> mine = heartbeatMonitor.ownSubscriptions();
        for (String host : gateway.distinctHosts()) {
            for (var c : redisAdminService.pubsubChannels(host)) {
                boolean self = mine.contains(c.name());
                long subs = self ? Math.max(0, c.subscribers() - 1) : c.subscribers();
                if (self && subs == 0) {
                    // 这个频道纯粹是我们订阅出来的，现场本来没有 —— 不计入
                    selfOnly.add(host + " " + c.name());
                    continue;
                }
                out.add(new DatahubChannelView(c.name(), subs, c.group(), c.instance(), self));
            }
        }
        return new ChannelScan(List.copyOf(out), List.copyOf(selfOnly));
    }

    /** 频道扫描结果：真实频道 + 「只因为我们才存在」的频道 */
    public record ChannelScan(List<DatahubChannelView> channels, List<String> selfOnlyChannels) {
    }

    /** 频道数；实例不可达时返回 0（不抛异常，节点列表不应因频道失败而整体失败） */
    private int safeChannelCount(String host) {
        try {
            return redisAdminService.pubsubChannels(host).size();
        } catch (Exception ex) {
            log.warn("取 {} 的频道数失败：{}", host, ex.getMessage());
            return 0;
        }
    }

    /* ================================= 流 ================================= */

    /**
     * 跨实例、跨 db 的 stream 清单。
     *
     * <p>这是「数据中台」与「消息总线」两页共用的接口。
     */
    public List<DatahubStreamView> listStreams(StreamQuery query) {
        List<DatahubStreamView> all = new ArrayList<>();
        for (String host : gateway.distinctHosts()) {
            if (StringUtils.hasText(query.getInstance()) && !host.equals(query.getInstance())) {
                continue;
            }
            for (int db = 0; db <= datahubProperties.getMaxDb(); db++) {
                if (query.getDb() != null && query.getDb() != db) {
                    continue;
                }
                StatefulRedisConnection<String, String> conn = gateway.connectionFor(host, db);
                if (conn == null) {
                    continue;
                }
                RedisCommands<String, String> cmd = conn.sync();
                for (String key : scanStreamKeys(cmd)) {
                    try {
                        long len = cmd.xlen(key);
                        String role = roleOf(key);
                        if (StringUtils.hasText(query.getRole()) && !query.getRole().equals(role)) {
                            continue;
                        }
                        if (StringUtils.hasText(query.getKeyword())
                                && !key.toLowerCase().contains(query.getKeyword().toLowerCase().trim())) {
                            continue;
                        }
                        all.add(new DatahubStreamView(key, len, groupsOf(cmd, key),
                                host, db, role));
                    } catch (Exception ex) {
                        log.warn("读流失败 {} db{} {}：{}", host, db, key, ex.getMessage());
                    }
                }
            }
        }
        // 默认按条目数倒序：最积压的排前面（现场 1400 万那条一眼就能看到）
        boolean asc = "asc".equalsIgnoreCase(query.getSortOrder());
        Comparator<DatahubStreamView> byLen =
                Comparator.comparingLong(DatahubStreamView::length);
        all.sort(asc ? byLen : byLen.reversed());
        return all;
    }

    /**
     * 读一个流的消费组（含 lag）；失败时返回空列表，不让整页失败。
     *
     * <p><b>为什么手动解析 {@code List<Object>}：</b>本项目用的 Lettuce 7.5.2
     * 里 {@code xinfoGroups()} 的返回类型是**原始列表**（{@code List<Object>}），
     * 没有像 {@code XInfoGroup} 这样的强类型模型 —— 那个类在这个版本里不存在
     * （编译期会直接报找不到符号）。所以这里按 Redis 的实际回包结构解析：
     * 每个组是一个「字段名、值、字段名、值…」的平坦列表。
     *
     * <p>实测回包（136/137，Redis 8.6.2）字段为：
     * <pre>
     *   name / consumers / pending / last-delivered-id / entries-read / lag
     * </pre>
     * ⚠️ {@code lag} 与 {@code entries-read} **可能整体缺失**（老版本 Redis 不返回），
     * 此时按 {@code null} 返回而不是 0 —— 0 表示「没有积压」，
     * 把「不知道」显示成「没有积压」会让运维漏掉真正的积压。
     *
     * <p>没有消费组的流（现场 137 的入口流就是这样）会直接抛错，
     * 那是正常情况，按空列表处理。
     */
    private List<StreamGroupView> groupsOf(RedisCommands<String, String> cmd, String key) {
        try {
            List<Object> raw = cmd.xinfoGroups(key);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<StreamGroupView> out = new ArrayList<>();
            for (Object item : raw) {
                Map<String, Object> g = asMap(item);
                if (g.isEmpty()) {
                    continue;
                }
                out.add(new StreamGroupView(
                        str(g.get("name")),
                        num(g.get("consumers")),
                        num(g.get("pending")),
                        str(g.get("last-delivered-id")),
                        // 缺失时为 null，**不用 0 冒充**
                        g.containsKey("lag") ? Long.valueOf(num(g.get("lag"))) : null));
            }
            return out;
        } catch (Exception ex) {
            // 没有消费组的流，XINFO GROUPS 会报错 —— 这是正常情况（现场 137 的入口流就是这样）
            log.debug("读消费组失败（可能本就没有消费组）{}：{}", key, ex.getMessage());
            return List.of();
        }
    }

    /** 把「字段名、值、字段名、值…」的平坦列表转成 Map（嵌套列表也兼容） */
    private static Map<String, Object> asMap(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<?> list;
        if (item instanceof List<?> l) {
            list = l;
        } else if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } else {
            return out;
        }
        for (int i = 0; i + 1 < list.size(); i += 2) {
            out.put(String.valueOf(list.get(i)), list.get(i + 1));
        }
        return out;
    }

    /** 转字符串；null 或空串都返回 null（不制造空字符串） */
    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    /** 转 long；取不到按 0（consumers/pending 缺失即视为 0，这两个字段不存在歧义） */
    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 流的角色分类 —— 与前端 {@code roleOf()} 保持**逐字一致**。
     *
     * <p>命名照现场实测：
     * <pre>
     *   DataHub_req_stream   → ingress   委托/策略服务器 → 中台
     *   DataHub_reply_stream → reply     中台回包
     *   ST-/WT-…-reply       → ack       回执
     *   ST-/WT-…             → downstream 中台下发
     * </pre>
     * 两边若不一致，页面上的筛选与后端下推的筛选会得出不同结果。
     */
    static String roleOf(String key) {
        if (key == null) {
            return "other";
        }
        if ("DataHub_req_stream".equals(key)) {
            return "ingress";
        }
        if ("DataHub_reply_stream".equals(key)) {
            return "reply";
        }
        if (key.endsWith("-reply")) {
            return "ack";
        }
        if (key.startsWith("ST-") || key.startsWith("WT-")) {
            return "downstream";
        }
        return "other";
    }

    /* ================================ 流条目 ================================ */

    /**
     * 取某个流的条目（**只在尾部窗口内**）。
     *
     * <p>⚠️ 现场入口流有 1400 万条，**绝不**能全量返回。做法是先 {@code XLEN}
     * 拿总长，再用 {@code XRANGE} 的区间只取最后 window 条 —— 这样即使流有 1 亿条，
     * 单次开销也只与 window 有关。
     *
     * <p>返回里带 {@code truncatedWindow}：让页面能说明「只查了最近 N 条」，
     * 否则「没查到这个 request_id」会被误读成「它不存在」。
     */
    public DatahubEntryPage listStreamEntries(String key, String instance, Integer db,
                                              Integer page, Integer pageSize, Integer window) {
        if (!StringUtils.hasText(key)) {
            throw BizException.badRequest("必须提供流名 key");
        }
        int windowSize = window == null || window < 1 ? DEFAULT_WINDOW : Math.min(window, MAX_WINDOW);
        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);

        // 定位到具体的 (实例, db)：不传就按顺序找到第一个拥有该流的
        for (String host : gateway.distinctHosts()) {
            if (StringUtils.hasText(instance) && !host.equals(instance)) {
                continue;
            }
            for (int d = 0; d <= datahubProperties.getMaxDb(); d++) {
                if (db != null && db != d) {
                    continue;
                }
                StatefulRedisConnection<String, String> conn = gateway.connectionFor(host, d);
                if (conn == null) {
                    continue;
                }
                RedisCommands<String, String> cmd = conn.sync();
                try {
                    if (!"stream".equals(cmd.type(key))) {
                        continue;
                    }
                    long total = cmd.xlen(key);
                    if (total == 0) {
                        return new DatahubEntryPage(List.of(), 0, p, ps,
                                windowSize, 0, false, host, d, key);
                    }
                    // 只取最后 windowSize 条：按 ID 区间从 (total-window) 开始
                    List<StreamMessage<String, String>> msgs = tail(cmd, key, windowSize);
                    boolean truncated = total > msgs.size();
                    // 尾部窗口内部再分页：最新的排在最前（与页面「看最新」的预期一致）
                    List<DatahubStreamEntryView> ordered = new ArrayList<>();
                    for (int i = msgs.size() - 1; i >= 0; i--) {
                        StreamMessage<String, String> m = msgs.get(i);
                        ordered.add(new DatahubStreamEntryView(m.getId(), m.getBody()));
                    }
                    int from = Math.min((p - 1) * ps, ordered.size());
                    int to = Math.min(from + ps, ordered.size());
                    return new DatahubEntryPage(List.copyOf(ordered.subList(from, to)),
                            ordered.size(), p, ps, windowSize, total, truncated, host, d, key);
                } catch (Exception ex) {
                    log.warn("读条目失败 {} db{} {}：{}", host, d, key, ex.getMessage());
                }
            }
        }
        throw BizException.notFound("找不到流 " + key
                + "（可能不存在，或不在 app.datahub.max-db 覆盖的 db 范围内）");
    }

    /**
     * 取一个流的最后 N 条（返回**升序**，最旧在前、最新在后）。
     *
     * <p>用 {@code XREVRANGE} 反向取尾部，再翻回正序 ——
     * 比「从头 XRANGE 再截断」高效得多：后者在 1400 万条的流上会先拉回一大堆。
     *
     * <p>⚠️ <b>区间参数是 {@code Range.create("-", "+")}，不是 {@code ("+", "-")}。</b>
     * 这与 {@code XREVRANGE key + -} 的直觉相反，但 Lettuce 7.5.2 就是这么映射的 ——
     * 实测（单机对比 {@code xrevrange/xrange}）：
     * <pre>
     *   XLEN = 20140
     *   xrevrange(("+","-"), 3) → 0 条          ← 静默返回空，不报错
     *   xrevrange(("-","+"), 3) → 最新 3 条      ← 正确（降序）
     *   xrange(("-","+"), 3)    → 最旧 3 条（升序）
     * </pre>
     * 写错的表现是「流明明有 2 万条，页面一条都看不到」，而且**没有任何报错**。
     */
    private List<StreamMessage<String, String>> tail(RedisCommands<String, String> cmd,
                                                     String key, int n) {
        List<StreamMessage<String, String>> desc =
                cmd.xrevrange(key, Range.create("-", "+"), Limit.from(n));
        List<StreamMessage<String, String>> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        return asc;
    }

    /* ================================ 请求追踪 ================================ */

    /**
     * 按 {@code request_id} 串联一次请求（入口 → 下发 → 回执）。
     *
     * <p>⚠️ <b>只能在尾部窗口内查找</b>，这是本接口的固有限制，不是偷懒：
     * 现场入口流有 1400 万条，逐条全扫要么超时、要么把 Redis 拖垮。
     * 所以：每个流的最近 {@code window} 条里找这个 request_id，
     * 并在返回里明确标出 {@code truncatedWindow=true}。
     *
     * <p><b>「没找到」不等于「不存在」</b> —— 页面必须把这句显示出来，
     * 否则排查的人会据此认定「这条请求根本没发出去」。
     */
    public DatahubTraceView trace(String requestId, Integer window) {
        if (!StringUtils.hasText(requestId)) {
            throw BizException.badRequest("必须提供 requestId");
        }
        int windowSize = window == null || window < 1 ? DEFAULT_WINDOW : Math.min(window, MAX_WINDOW);
        String needle = requestId.trim();
        List<TraceHit> hits = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        boolean anyTruncated = false;

        for (String host : gateway.distinctHosts()) {
            for (int db = 0; db <= datahubProperties.getMaxDb(); db++) {
                StatefulRedisConnection<String, String> conn = gateway.connectionFor(host, db);
                if (conn == null) {
                    continue;
                }
                RedisCommands<String, String> cmd = conn.sync();
                for (String key : scanStreamKeys(cmd)) {
                    try {
                        long len = cmd.xlen(key);
                        if (len == 0) {
                            continue;
                        }
                        List<StreamMessage<String, String>> msgs = tail(cmd, key, windowSize);
                        boolean truncated = len > msgs.size();
                        anyTruncated = anyTruncated || truncated;
                        searched.add(host + "/db" + db + "/" + key + "(" + msgs.size() + "条)");
                        for (StreamMessage<String, String> m : msgs) {
                            if (matchesRequestId(m.getBody(), needle)) {
                                hits.add(new TraceHit(host, db, key, m.getId(), m.getBody()));
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("追踪时读流失败 {} db{} {}：{}", host, db, key, ex.getMessage());
                    }
                }
            }
        }
        return new DatahubTraceView(needle, hits, searched, windowSize, anyTruncated, !hits.isEmpty());
    }

    /**
     * 该条目是否属于这个 request_id。
     *
     * <p>同时认两种位置：{@code request_id} 字段本身，或 {@code task} 里内嵌的
     * {@code request_id}（现场两种都出现过）。这里只做「精确等于」或
     * 「字段值里包含」，不做正则 —— 猜测性的模糊匹配会把不相干的条目串进来。
     */
    private static boolean matchesRequestId(Map<String, String> body, String needle) {
        if (body == null) {
            return false;
        }
        String direct = body.get("request_id");
        if (needle.equals(direct)) {
            return true;
        }
        String task = body.get("task");
        return task != null && task.contains(needle);
    }

    /* ================================ 扫描工具 ================================ */

    /** 扫出该 db 里全部 stream 键（受上限约束） */
    private List<String> scanStreamKeys(RedisCommands<String, String> cmd) {
        List<String> out = new ArrayList<>();
        ScanArgs args = ScanArgs.Builder.limit(SCAN_COUNT);
        KeyScanCursor<String> cursor = cmd.scan(args);
        int rounds = 0;
        while (true) {
            for (String key : cursor.getKeys()) {
                try {
                    if ("stream".equals(cmd.type(key))) {
                        out.add(key);
                    }
                } catch (Exception ignored) {
                    // 单个键类型读失败不影响其它键
                }
                if (out.size() >= datahubProperties.getScanLimit()) {
                    log.warn("SCAN 达到上限 {}，结果可能不完整", datahubProperties.getScanLimit());
                    return out;
                }
            }
            if (cursor.isFinished() || ++rounds > MAX_SCAN_ROUNDS) {
                break;
            }
            cursor = cmd.scan(cursor, args);
        }
        return out;
    }

    /** 扫一遍该 db，统计 stream 数与条目合计（节点列表用） */
    private StreamStat scanStreams(RedisCommands<String, String> cmd) {
        long entries = 0;
        int count = 0;
        for (String key : scanStreamKeys(cmd)) {
            try {
                entries += cmd.xlen(key);
                count++;
            } catch (Exception ignored) {
                // 忽略单个键
            }
        }
        return new StreamStat(entries, count);
    }

    private record StreamStat(long entryTotal, int streamTotal) {
    }

    /** 取 string 键的整数值；不存在或不是整数返回 null（**不用 0 冒充**） */
    private static Long safeGetLong(RedisCommands<String, String> cmd, String key) {
        try {
            if (!"string".equals(cmd.type(key))) {
                return null;
            }
            String v = cmd.get(key);
            return v == null ? null : Long.parseLong(v.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    /** 配置里声明的实例数（meta 用） */
    public int configuredInstanceCount() {
        return redisProperties.getInstances() == null ? 0 : redisProperties.getInstances().size();
    }
}
