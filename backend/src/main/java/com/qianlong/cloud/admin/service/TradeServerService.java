package com.qianlong.cloud.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.config.TradeServerProperties;
import com.qianlong.cloud.admin.dto.AccountDtos.RedisAccountView;
import com.qianlong.cloud.admin.dto.AccountDtos.TraderServerDto;
import com.qianlong.cloud.admin.dto.TradeServerDtos.AssignmentQuery;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TradeServerListMeta;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TradeServerPage;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TraderServerQuery;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TraderServerView;
import com.qianlong.cloud.admin.service.RegistryArrayReader.RegistryEntry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 委托服务器（WT 方向）：节点清单 + 账号分配关系。
 *
 * <h2>只读，不做启停</h2>
 * 本模块**没有写接口**，一个 Redis 写命令都没有。
 * 现场 {@code traderserver} 键与 {@code acc-*} 都是生产正在用的对象
 * （同一套键里既有真机 {@code 20474789c674Test} 也有历史残留），
 * 而后端没有「这台机器到底是不是我们在管的实例」这一信息 ——
 * 按 id/mac 猜着写回，很容易改到一台并不属于本次运维范围的机器。
 * 所以只呈现状态，启停由现场按自己的流程操作。
 *
 * <h2>三个必须照现场如实呈现的事实（都是实测，不是猜测）</h2>
 * <ol>
 *   <li><b>注册表在多个实例上各存一份且内容不同</b>
 *       （136 db0 两条测试残留、136 db1 七条、137 db1 四条已过期 63 天），
 *       所以后端**跨实例全读并全部列出**，不按 id 去重。</li>
 *   <li><b>{@code id} 会重复</b>：136 db1 的 7 条里有 **4 个 {@code id=0}**。
 *       因此行的唯一键是 {@code (来源实例, 数组下标)}，接口把
 *       {@code instance} 与 {@code arrayIndex} 一并返回。</li>
 *   <li><b>在线判定只看 {@code lastActiveTimeLong}</b>：现场只有一台
 *       （137 的 {@code id=1}）每 10 秒刷新心跳，其余全部是「enable=true 但几个月前
 *       的陈旧时间」—— 若照 {@code enable} 判断，页面会显示一堆「在线」，
 *       而那与实际完全相反。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServerService {

    private final TraderServerRepository repository;
    private final TradeServerProperties tradeProperties;
    private final AccountService accountService;

    /* ================================= 节点清单 ================================= */

    /**
     * 委托服务器节点列表（筛选 + 排序 + 分页）。
     *
     * <p>分页在**后端**做：注册表跨三个库摊平后条数不多（现场 13 条），
     * 但将来若某个库累积大量历史残留，前端也还是要能分页翻。
     */
    public TradeServerPage listServers(TraderServerQuery query) {
        List<RegistryEntry> entries = repository.readRegistry();
        List<TraderServerView> all = new ArrayList<>();
        int onlineCount = 0;
        for (RegistryEntry entry : entries) {
            TraderServerView view = toView(entry);
            if (Boolean.TRUE.equals(view.online())) {
                onlineCount++;
            }
            all.add(view);
        }
        // 注册表份数 = 有该键的实例数。entry 摊平后已经丢了「哪几条来自同一份」，
        // 用来源实例去重数一下即可（同一实例只会读一次该键）。
        long copies = entries.stream().map(RegistryEntry::instanceDisplay).distinct().count();

        List<TraderServerView> filtered = new ArrayList<>();
        for (TraderServerView row : all) {
            if (matches(row, query)) {
                filtered.add(row);
            }
        }
        sort(filtered, query.getSortBy(), query.getSortOrder(), query.getOnline());

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());

        TradeServerListMeta meta = new TradeServerListMeta(
                (int) copies,
                all.size(),
                onlineCount,
                tradeProperties.getOfflineAfterSec(),
                repository.configuredInstanceCount(),
                repository.availableInstanceNames(),
                List.copyOf(repository.unavailableInstances().keySet()),
                tradeProperties.getRegistryKey(),
                repository.channels().onlineChannel(),
                repository.channels().beatChannel(),
                repository.channels().offlineChannel());

        if (!meta.redisUnavailable().isEmpty()) {
            log.warn("有 {} 个 Redis 实例不可用，本次委托服务器节点列表可能不完整：{}",
                    meta.redisUnavailable().size(), meta.redisUnavailable());
        }
        return new TradeServerPage(
                List.copyOf(filtered.subList(from, to)), filtered.size(), page, pageSize, meta);
    }

    /* ============================== 账号分配关系 ============================== */

    /**
     * 账号 → 委托服务器的分配关系。
     *
     * <p>数据源是 {@code acc-*} 键里的 {@code traderserver} 字段，
     * 且**直接复用账号页的映射**（见 {@code AccountService.listRedisAccounts}）——
     * 前端这两个页面用同一个 {@code RedisAccount} 类型，映射只应有一份。
     *
     * <p>过滤口径与前端 mock 一致：
     * <ul>
     *   <li>{@code keyword} —— 账号 / 用户账号 / 户名，任一命中即可；</li>
     *   <li>{@code online} —— {@code onlineflag == 1}；</li>
     *   <li>{@code serverId} —— 按分配对象的 {@code id} 匹配（现场 id 会重复，
     *       所以这里说明是「所有 id 等于它的行」，不是「某一台机器」）。</li>
     * </ul>
     */
    public MergedAssignments listAssignments(AssignmentQuery query) {
        List<RedisAccountView> all = accountService.listRedisAccounts();
        List<RedisAccountView> filtered = new ArrayList<>();
        for (RedisAccountView row : all) {
            if (matches(row, query)) {
                filtered.add(row);
            }
        }
        sortAssignments(filtered, query.getSortBy(), query.getSortOrder());

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        int from = Math.min((page - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return new MergedAssignments(List.copyOf(filtered.subList(from, to)), filtered.size());
    }

    /**
     * 分配结果 + 元信息。
     *
     * @param rows  当前页
     * @param total **筛选后**的总数（不是 Redis 里的 acc-* 原始数量）——
     *              用原始数量会让表格出现「共 14 条、筛选后 1 条、翻页翻出空白」
     */
    public record MergedAssignments(List<RedisAccountView> rows, long total) {
    }

    /**
     * 某台委托服务器承载的账号（节点详情抽屉的下钻）。
     *
     * <p>⚠️ 按 {@code id} 匹配 —— 而现场 {@code id} 会重复
     * （136 db1 的 7 条里有 4 个 {@code id=0}）。所以这个下钻**天然无法区分
     * 那几个同 id 的节点**，结果是「它们显示承载同一批账号」。
     * 这是数据本身的问题，不是查询写错了：接口照实返回，
     * 页面也已写明「按 id 匹配，因 id 会重复仅供参考」。
     * 不去猜一个更细的匹配规则（比如再拿 ip 过滤）—— 那会把
     * 「本该看到的重复」藏起来。
     */
    public List<RedisAccountView> listAccountsByServer(Integer serverId) {
        if (serverId == null) {
            throw BizException.badRequest("serverId 不能为空");
        }
        List<RedisAccountView> out = new ArrayList<>();
        for (RedisAccountView row : accountService.listRedisAccounts()) {
            TraderServerDto ts = traderServerOf(row);
            if (ts != null && Objects.equals(ts.id(), serverId)) {
                out.add(row);
            }
        }
        return out;
    }

    /* ================================= 筛选 ================================= */

    private boolean matches(TraderServerView row, TraderServerQuery q) {
        String keyword = trimToNull(q.getKeyword());
        if (keyword != null) {
            // 前端 placeholder 写的是「搜索 IP 或 MAC」，就按这两个字段匹配；
            // 额外认 instance（来源实例），便于按库排查「这批记录是哪来的」
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

    private boolean matches(RedisAccountView row, AssignmentQuery q) {
        String keyword = trimToNull(q.getKeyword());
        if (keyword != null) {
            boolean hit = contains(row.account(), keyword)
                    || contains(row.useraccount(), keyword)
                    || contains(row.name(), keyword);
            if (!hit) {
                return false;
            }
        }
        if (q.getOnline() != null) {
            boolean online = row.onlineflag() != null && row.onlineflag() == 1;
            if (online != q.getOnline()) {
                return false;
            }
        }
        if (q.getServerId() != null) {
            TraderServerDto ts = traderServerOf(row);
            if (ts == null || !Objects.equals(ts.id(), q.getServerId())) {
                return false;
            }
        }
        return true;
    }

    /* ================================= 排序 ================================= */

    /**
     * 排序白名单：未知名直接报错，**不静默忽略**。
     *
     * <p>前端表格对「节点 ID」「最后活跃」两列开了 sorter（见 {@code TradeServer.vue}
     * 的 {@code sorter: true}），列 key 分别是 {@code id} 与 {@code lastActiveTimeLong}。
     *
     * <p>{@code online} 的默认序有讲究：前端进页面默认「全部」，此时按
     * **在线优先 + 心跳时间倒序**排 —— 现场绝大多数记录是几个月前的残留，
     * 若不这么排，进页面第一眼看到的全是死节点，唯一活着的那台被挤到后面，
     * 而「现在到底有没有活的委托服务器」正是这个页面要回答的第一个问题。
     * 用户显式点了列排序时，以用户的选择为准。
     */
    private void sort(List<TraderServerView> rows, String sortBy, String sortOrder, Boolean onlineFilter) {
        String field = trimToNull(sortBy);
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        Comparator<TraderServerView> comparator;
        if (field == null) {
            if (onlineFilter == null) {
                // 默认视图：在线的排前面，各自按心跳倒序（最新的在最上）
                rows.sort(Comparator
                        .comparing((TraderServerView r) -> !Boolean.TRUE.equals(r.online()))
                        .thenComparing(r -> r.lastActiveTimeLong() == null ? 0L : -r.lastActiveTimeLong()));
                return;
            }
            comparator = Comparator.comparing(r -> r.lastActiveTimeLong() == null ? 0L : r.lastActiveTimeLong());
        } else if ("id".equals(field)) {
            comparator = Comparator.comparing(r -> r.id() == null ? Integer.MIN_VALUE : r.id());
        } else if ("lastActiveTimeLong".equals(field)) {
            comparator = Comparator.comparing(r -> r.lastActiveTimeLong() == null ? 0L : r.lastActiveTimeLong());
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

    private void sortAssignments(List<RedisAccountView> rows, String sortBy, String sortOrder) {
        String field = trimToNull(sortBy);
        boolean desc = !"asc".equalsIgnoreCase(sortOrder);
        Comparator<RedisAccountView> comparator;
        if (field == null || "account".equals(field)) {
            comparator = Comparator.comparing(r -> nullSafe(r.account()));
        } else if ("id".equals(field)) {
            comparator = Comparator.comparing(r -> r.id() == null ? Integer.MIN_VALUE : r.id());
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
     * <p>字段名照现场：注册表里是 {@code last-active-time}（带横线）与
     * {@code lastActiveTimeLong}；{@code id} / {@code enable} 允许缺失
     * （现场有记录缺字段，缺失就返回 null，**不用 0/false 顶替** ——
     * 0 在现场是真实存在的 id，"缺失" 与 "id 就是 0" 是两件事）。
     */
    private TraderServerView toView(RegistryEntry entry) {
        JsonNode n = entry.json();
        Long ts = n.hasNonNull("lastActiveTimeLong") ? n.get("lastActiveTimeLong").asLong() : null;
        Boolean enable = n.hasNonNull("enable") ? n.get("enable").asBoolean() : null;
        long age = ageSec(ts);
        return new TraderServerView(
                n.hasNonNull("id") ? n.get("id").asInt() : null,
                text(n, "ip"),
                text(n, "mac"),
                enable,
                text(n, "last-active-time"),
                ts,
                isOnline(enable, ts),
                ts == null ? null : age,
                entry.instanceDisplay(),
                entry.index());
    }

    /**
     * 在线判定：**只看心跳时间**，不看 {@code enable}。
     *
     * <p>为什么不看 {@code enable}：现场 136 db0 的两条测试残留
     * {@code enable=true} 但最后活跃是 22 小时前；137 db1 的四条同样
     * {@code enable=true} 却停在 63 天前。若把 enable 也算作在线条件，
     * 页面会显示一片「在线」，与实际完全相反。
     *
     * <p>反之，{@code enable=false} 的**一定**不算在线（现场
     * 136 db1 那条 {@code id=0} 就是被显式停用的历史记录）。
     *
     * <p>⚠️ 与前端 {@code isServerOnline} 是同一判定的两个实现
     * （前端那个会随时间跳变，不需要刷新页面）。两边的阈值由
     * {@link TradeServerProperties#getOfflineAfterSec()} 统一，
     * 接口在 meta 里回传该值供页面自检。
     */
    private boolean isOnline(Boolean enable, Long ts) {
        if (Boolean.FALSE.equals(enable)) {
            return false;
        }
        if (ts == null) {
            return false;
        }
        return ageSec(ts) <= tradeProperties.getOfflineAfterSec();
    }

    /** 心跳年龄（秒）。未来时间戳按 0 处理，不返回负数（时钟偏差不该显示成「-3 秒前」） */
    private static long ageSec(Long ts) {
        if (ts == null) {
            return Long.MAX_VALUE;
        }
        long now = Instant.now().getEpochSecond();
        long age = now - ts;
        return Math.max(0L, age);
    }

    /**
     * 取一个 acc 行里内嵌的 traderserver 分配。
     *
     * <p>返回 {@link TraderServerDto}（{@code AccountService} 已映射好），
     * 这里直接拿来用、不重新解析 JSON —— 映射只应有一份。
     *
     * <p>{@code id=-1} 表示**未分配**（现场 136 db0 的
     * {@code acc-010100011300_7_6} 就是这样，且 {@code ip} 为空串）。
     * 所以按「没有分配」处理，避免页面把它显示成「分配到了 id=-1 的机器」。
     */
    private static TraderServerDto traderServerOf(RedisAccountView row) {
        TraderServerDto ts = row.traderserver();
        if (ts == null || ts.id() == null || ts.id() < 0) {
            return null;
        }
        return ts;
    }

    /* ================================ 小工具 ================================ */

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        // 现场有空串，统一按「没有」处理，避免页面出现空白格与空值两种显示
        return s == null || s.isEmpty() ? null : s;
    }

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
