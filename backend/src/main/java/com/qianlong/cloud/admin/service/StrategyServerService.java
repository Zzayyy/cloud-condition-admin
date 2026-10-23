package com.qianlong.cloud.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.config.StrategyServerProperties;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerListMeta;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerPage;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerQuery;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerView;
import com.qianlong.cloud.admin.service.RegistryArrayReader.RegistryEntry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 策略服务器（ST 方向）：节点清单 + 在线状态 + 承载条件单用户数。
 *
 * <h2>只读，不做启停</h2>
 * 本模块**没有写接口**。现场 {@code strategy-srv} 里的记录 {@code id} 全是 0、
 * {@code mac} 又各自带实例后缀（{@code …QLDataHub1} / {@code …probe}），
 * <b>没有任何字段能稳定地指回「同一台机器」</b> ——
 * 按 id 或 mac 猜着写回很容易改到一台并不属于本次运维范围的机器上。
 * 所以只呈现状态，启停由现场按自己的流程操作。
 *
 * <h2>⚠️ 现场现状：策略服务器未开放（实测 2026-10-15）</h2>
 * <ol>
 *   <li><b>注册表不刷新。</b>盯 30 秒内容完全不变：
 *       136 db0 那条停在当天 10:01，136 db1 那条停在 9/24（21 天前）。</li>
 *   <li><b>心跳频道上没有心跳。</b>{@code strategyserver_beat_1} 频道存在，
 *       但订阅 25 秒收到 0 条（带/不带后缀两种拼法都订过）。</li>
 * </ol>
 * 因此：
 * <ul>
 *   <li><b>不加常驻订阅</b> —— 加了也收不到；更糟的是 Redis 的
 *       {@code PUBSUB CHANNELS} 只列有订阅者的频道，我们一订阅就会凭空
 *       多出一个订阅者，把 Redis 管理页的数字弄脏（数据中台那边已踩过）。</li>
 *   <li>「在线」按 {@code lastActiveTimeLong} 判，结果接近 0 台，
 *       页面另有 {@code registryStale} 提示说清「注册表没在更新」，
 *       避免被读成「策略平台全挂了」。</li>
 * </ul>
 *
 * <h2>⚠️ usecount 现场全部为 0</h2>
 * 这一列是页面的核心内容（「承载条件单用户数」，还带进度条），
 * 但现场 3 条记录全为 0。那是「服务未开放、没有上报」的正常结果，
 * <b>不等于「策略平台一个用户都没承载」</b> —— 所以 meta.note 会说明这一点，
 * 页面也在列头标注。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyServerService {

    private final RedisJsonGateway gateway;
    private final StrategyServerProperties properties;

    /**
     * 策略服务器节点列表（跨实例全读 + 筛选 + 排序 + 分页）。
     *
     * <p>汇总（在线数 / usecount 合计与最大值）由**后端在全量上算**，
     * 通过 meta 回传 —— 前端按当前页 filter 只能得出「第 1 页有几台在线」，
     * 而页面写的是「在线节点数」，翻页时数字会莫名变化。
     */
    public StrategyServerPage listServers(StrategyServerQuery query) {
        List<RegistryEntry> entries =
                RegistryArrayReader.flatten(gateway.findAllAcrossInstances(properties.getRegistryKey()),
                        properties.getRegistryKey());

        // ⚠️ 必须先按 mac 归并再统计，否则"台数 / usecount 合计"都是错的：
        //    现场 2 条只对应 1 台机器（136 db0 与 136 db1 各注册一份，
        //    usecount 一份 19、一份 0 —— 不归并就会算出 19 和两根柱）。
        List<StrategyServerView> raw = new ArrayList<>();
        for (RegistryEntry entry : entries) {
            raw.add(toView(entry));
        }
        List<StrategyServerView> all = dedupeByMac(raw);

        int onlineCount = 0;
        long usecountTotal = 0;
        int usecountMax = 0;
        Long newestAge = null;

        for (StrategyServerView view : all) {
            if (Boolean.TRUE.equals(view.online())) {
                onlineCount++;
            }
            int uc = view.usecount() == null ? 0 : view.usecount();
            usecountTotal += uc;
            usecountMax = Math.max(usecountMax, uc);
            // 注册表陈旧度取**最新**那条：只要还有一台在正常刷新，注册表就不算陈旧
            if (view.ageSec() != null && (newestAge == null || view.ageSec() < newestAge)) {
                newestAge = view.ageSec();
            }
        }

        List<StrategyServerView> filtered = new ArrayList<>();
        for (StrategyServerView row : all) {
            if (matches(row, query)) {
                filtered.add(row);
            }
        }
        sort(filtered, query.getSortBy(), query.getSortOrder(), query.getOnline());

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());

        boolean stale = newestAge == null || newestAge > properties.getStaleRegistryAfterSec();

        StrategyServerListMeta meta = new StrategyServerListMeta(
                onlineCount,
                all.size() - onlineCount,
                all.size(),
                raw.size(),
                usecountTotal,
                usecountMax,
                properties.getOfflineAfterSec(),
                RegistryArrayReader.copiesOf(entries),
                properties.getRegistryKey(),
                gateway.availableInstanceNames(),
                List.copyOf(gateway.unavailableInstances().keySet()),
                newestAge,
                stale,
                properties.getStaleRegistryAfterSec(),
                buildNote(stale, newestAge, usecountTotal, all.size()));

        if (!meta.redisUnavailable().isEmpty()) {
            log.warn("有 {} 个 Redis 实例不可用，本次策略服务器列表可能不完整：{}",
                    meta.redisUnavailable().size(), meta.redisUnavailable());
        }
        return new StrategyServerPage(List.copyOf(filtered.subList(from, to)),
                filtered.size(), page, pageSize, meta);
    }

    /**
     * 现场现状说明。
     *
     * <p>这几句话是**必需的**，不是装饰：
     * <ul>
     *   <li>不说「注册表未刷新」，21 天前的时间戳会被读成策略平台挂了；</li>
     *   <li>不说「usecount 未上报」，那一列全 0 会被读成「没承载任何用户」；</li>
     *   <li>不说「心跳收不到是预期」，会有人去查为什么没有心跳。</li>
     * </ul>
     */
    private String buildNote(boolean stale, Long newestAge, long usecountTotal, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("在线按 lastActiveTimeLong 判定（阈值 ")
                .append(properties.getOfflineAfterSec())
                .append(" 秒）。本模块**不订阅**心跳频道 —— 策略服务器未开放时心跳本来就不通，")
                .append("订阅只会收不到，还会污染 Redis 的频道订阅者数。");
        if (stale) {
            sb.append("⚠️ 注册表已 ")
                    .append(newestAge == null ? "始终没有可用的心跳时间" : (newestAge + " 秒"))
                    .append("未刷新，说明策略服务器当前未开放 —— ")
                    .append("「离线」是必然结果，不必据此排查节点。");
        }
        if (total > 0 && usecountTotal == 0) {
            sb.append("⚠️ usecount 全部为 0（现场该字段未上报），")
                    .append("不代表策略平台没有承载条件单用户。");
        }
        return sb.toString();
    }

    /* ================================= 筛选 ================================= */

    private boolean matches(StrategyServerView row, StrategyServerQuery q) {
        String keyword = trimToNull(q.getKeyword());
        if (keyword != null) {
            // 前端 placeholder 是「搜索 IP 或 MAC」；额外认 instance（来源实例）便于按库排查
            boolean hit = contains(row.ip(), keyword)
                    || contains(row.mac(), keyword)
                    || contains(row.instance(), keyword);
            if (!hit) {
                return false;
            }
        }
        if (q.getOnline() != null && !Objects.equals(row.online(), q.getOnline())) {
            return false;
        }
        return true;
    }

    /* ================================= 排序 ================================= */

    /**
     * 排序白名单：未知名直接报错，**不静默忽略**。
     *
     * <p>前端表格开了排序的列 key 是 {@code id}、{@code usecount}、
     * {@code lastActiveTimeLong}（见 {@code StrategyServer.vue} 的 {@code sorter: true}）。
     *
     * <p>默认序：**在线优先 + usecount 倒序**。理由与委托服务器一致 ——
     * 进页面第一眼应该先看到活着的、承载最多的节点；
     * 若按 id 排，现场那两条 id 都是 0，顺序毫无意义。
     */
    private void sort(List<StrategyServerView> rows, String sortBy, String sortOrder,
                      Boolean onlineFilter) {
        String field = trimToNull(sortBy);
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        Comparator<StrategyServerView> comparator;
        if (field == null) {
            if (onlineFilter == null) {
                rows.sort(Comparator
                        .comparing((StrategyServerView r) -> !Boolean.TRUE.equals(r.online()))
                        .thenComparing(r -> -(r.usecount() == null ? 0 : r.usecount())));
                return;
            }
            comparator = Comparator.comparing(r -> r.usecount() == null ? 0 : r.usecount());
        } else if ("id".equals(field)) {
            comparator = Comparator.comparing(r -> r.id() == null ? Integer.MIN_VALUE : r.id());
        } else if ("usecount".equals(field)) {
            comparator = Comparator.comparing(r -> r.usecount() == null ? 0 : r.usecount());
        } else if ("lastActiveTimeLong".equals(field)) {
            comparator = Comparator.comparing(
                    r -> r.lastActiveTimeLong() == null ? 0L : r.lastActiveTimeLong());
        } else if ("ip".equals(field)) {
            comparator = Comparator.comparing(r -> nullSafe(r.ip()));
        } else {
            throw BizException.badRequest("不支持按 " + field + " 排序");
        }
        if (desc) {
            comparator = comparator.reversed();
        }
        rows.sort(comparator);
    }

    /* ================================= 映射 ================================= */

    /**
     * 一条注册表记录 → 接口出参。
     *
     * <p>字段名照现场：{@code login-time} / {@code last-active-time}（带横线）、
     * {@code lastActiveTimeLong}。{@code id} / {@code enable} / {@code usecount}
     * 允许缺失（缺失就返回 {@code null}，**不用 0/false 顶替** ——
     * 0 在现场是真实存在的值，「缺失」与「就是 0」是两件事）。
     */
    private StrategyServerView toView(RegistryEntry entry) {
        JsonNode n = entry.json();
        Long ts = RegistryArrayReader.longValue(n, "lastActiveTimeLong");
        Boolean enable = RegistryArrayReader.bool(n, "enable");
        long age = ageSec(ts);
        return new StrategyServerView(
                RegistryArrayReader.integer(n, "id"),
                RegistryArrayReader.text(n, "ip"),
                RegistryArrayReader.text(n, "mac"),
                RegistryArrayReader.integer(n, "usecount"),
                RegistryArrayReader.text(n, "login-time"),
                RegistryArrayReader.text(n, "last-active-time"),
                ts,
                enable,
                isOnline(enable, ts),
                ts == null ? null : age,
                entry.instanceDisplay(),
                entry.index(),
                1,
                List.of());
    }

    /**
     * 按 {@code mac} 归并同一台机器在多个注册表里的副本。
     *
     * <h2>为什么必须归并</h2>
     * 现场 {@code strategy-srv} 在 136 db0 与 136 db1 各存一份，
     * 摊平后 **2 条只对应 1 台机器**，而且两份的 {@code usecount} 还不一样
     * （db0 是 19、db1 是 0）。不归并的话：页面"节点总数"显示 2、
     * 「各节点承载条件单用户数」图会画出两根柱（19 和 0）——
     * 那是**错误的读数**，不是"细节多了一点"。
     *
     * <h2>为什么用 mac 而不是 id</h2>
     * {@code id} 现场全是 0（不可靠）。{@code mac} 形如
     * {@code 20474789c674QLDataHub2}（12 位 mac + 程序名）是稳定标识。
     *
     * <h2>保留哪一份</h2>
     * 保留 {@code lastActiveTimeLong} 最新的那份 —— 它代表"这台机器最近一次
     * 在哪个库里活动"。其余副本不丢弃，压成 {@code copies} 计数 +
     * {@code otherInstances} 列表，让「多副本」仍然可查。
     *
     * <p>没有 {@code mac} 的记录**不参与归并**：无从判断是否同一台，
     * 硬合并会把不同机器并成一台。
     */
    private List<StrategyServerView> dedupeByMac(List<StrategyServerView> raw) {
        Map<String, List<StrategyServerView>> byMac = new LinkedHashMap<>();
        List<StrategyServerView> noMac = new ArrayList<>();
        for (StrategyServerView v : raw) {
            String mac = trimToNull(v.mac());
            if (mac == null) {
                noMac.add(v);
                continue;
            }
            byMac.computeIfAbsent(mac, k -> new ArrayList<>()).add(v);
        }

        List<StrategyServerView> out = new ArrayList<>(noMac);
        for (List<StrategyServerView> group : byMac.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            StrategyServerView latest = group.stream()
                    .max(Comparator.comparingLong(v -> v.lastActiveTimeLong() == null
                            ? Long.MIN_VALUE : v.lastActiveTimeLong()))
                    .orElse(group.get(0));
            List<String> others = group.stream()
                    .filter(v -> v != latest)
                    .map(StrategyServerView::instance)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            out.add(new StrategyServerView(
                    latest.id(), latest.ip(), latest.mac(), latest.usecount(),
                    latest.loginTime(), latest.lastActiveTime(), latest.lastActiveTimeLong(),
                    latest.enable(), latest.online(), latest.ageSec(),
                    latest.instance(), latest.arrayIndex(),
                    group.size(), others));
        }
        return out;
    }

    /**
     * 在线判定：**只看心跳时间**，不看 {@code enable}。
     *
     * <p>与委托服务器同一个口径：现场存在 {@code enable=true} 但几个月前的
     * 陈旧记录，把 enable 也算作在线条件会显示一片「在线」，与实际相反。
     * 反之 {@code enable=false} 的**一定**不算在线。
     *
     * <p>⚠️ 现场策略服务器未开放，注册表不刷新，所以结果接近 0 台 ——
     * 那是事实，不是查询错误。页面用 {@code registryStale} 说明原因。
     */
    private boolean isOnline(Boolean enable, Long ts) {
        if (Boolean.FALSE.equals(enable)) {
            return false;
        }
        if (ts == null) {
            return false;
        }
        return ageSec(ts) <= properties.getOfflineAfterSec();
    }

    /** 心跳年龄（秒）。未来时间戳按 0 处理（时钟偏差不该显示成「-3 秒前」） */
    private static long ageSec(Long ts) {
        if (ts == null) {
            return Long.MAX_VALUE;
        }
        long age = Instant.now().getEpochSecond() - ts;
        return Math.max(0L, age);
    }

    /* ================================ 小工具 ================================ */

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
