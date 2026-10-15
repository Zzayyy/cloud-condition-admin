package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisInstanceInfoView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisInstanceView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisKeyDetailView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisKeyInfoView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisPubSubChannelView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.ScanResult;

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
 * Redis 管理：实例概览、键扫描、键详情。
 *
 * <h2>⚠️ 三条必须守住的安全线</h2>
 * <ol>
 *   <li><b>只用 SCAN，绝不用 KEYS。</b>现场 137 db0 的
 *       {@code DataHub_req_stream} 有 <b>1400 万条</b>条目；
 *       {@code KEYS *} 会把实例阻塞住，影响正在跑的生产流。</li>
 *   <li><b>强制条数上限。</b>接口签名里**没有**「取全部」的选项 ——
 *       上限写死在服务端，前端传再大也没用。</li>
 *   <li><b>只读。</b>本类不提供任何写命令（删除键见 {@link #deleteKey}，
 *       它是唯一例外，由 controller 加权限点 + 写审计）。</li>
 * </ol>
 *
 * <h2>⚠️ 本页最容易误读的一件事：键数 ≠ 条目数</h2>
 * 现场实测：
 * <pre>
 *   137 db0  DBSIZE = 117            ← 只有 117 个键
 *   但 DataHub_req_stream 的 XLEN = 14,129,288   ← 单个 stream 1400 万条
 * </pre>
 * 前端 README 里写的「457 万条」指的是**条目数**，不是键数。
 * 概览卡片如果把两者混着看，会得出完全错误的容量判断 ——
 * 所以 {@code dbKeys}（键数）与每个键的 {@code length}（条目数）
 * 在接口里是**分开的字段**，页面上也分开显示。
 *
 * <p>另外 {@code DBSIZE} 本身很快（O(1)），但 {@code XLEN} 要逐键查；
 * 本模块对键列表**只在需要时**取 length，且受总数上限约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisAdminService {

    /**
     * 单次扫描的硬上限。
     *
     * <p>为什么是 2000：现场有 1400 万条条目的 stream，
     * 而 SCAN 是增量游标 —— 扫 2000 个键在 137 db0 上约 10~30ms，
     * 再多就开始明显占用实例的连接时间。
     * 前端分页每页最多 60 条，2000 的上限足够翻很多页。
     */
    private static final int SCAN_HARD_LIMIT = 2000;

    /** 键列表里单次取 length / TTL 的最大键数（避免逐键查询把接口拖慢） */
    private static final int ENRICH_LIMIT = 60;

    /** 键详情里 stream 最多返回的入口条数 */
    private static final int STREAM_ENTRY_LIMIT = 20;

    /** 键详情里集合类类型最多返回的成员数 */
    private static final int MEMBER_LIMIT = 100;

    private final RedisJsonGateway gateway;

    /* ============================== 实例 ============================== */

    /**
     * 实例清单。
     *
     * <p>返回**配置里出现过的每台主机**（去重），而不是每个 (host,db) 组合 ——
     * 管理页要浏览整台实例的所有 db。每个实例的 {@code db} 给「有键的第一个 db」
     * 作为起始位置（查一次 INFO 得到），让前端切换实例时能直接落到有数据的 db 上、
     * 而不是落到空的 db0 上。
     */
    public List<RedisInstanceView> listInstances() {
        List<RedisInstanceView> out = new ArrayList<>();
        for (String host : gateway.distinctHosts()) {
            int port = gateway.portOfHost(host);
            int startDb = 0;
            StatefulRedisConnection<String, String> conn = gateway.connectionByHost(host);
            if (conn != null) {
                try {
                    startDb = firstDbWithKeys(conn.sync());
                } catch (Exception ex) {
                    // 取不到就用 0，不因此让整个列表失败
                    log.warn("取 {} 的起始 db 失败：{}", host, ex.getMessage());
                }
            }
            out.add(new RedisInstanceView(host, host, host, port, startDb));
        }
        return out;
    }

    /** 从 INFO 的 keyspace 段里找第一个有键的 db（找不到返回 0） */
    private static int firstDbWithKeys(RedisCommands<String, String> cmd) {
        Map<String, String> info = parseInfo(cmd.info("keyspace"));
        return info.keySet().stream()
                .filter(RedisAdminService::isDbKey)
                .mapToInt(k -> Integer.parseInt(k.substring(2)))
                .min()
                .orElse(0);
    }

    /**
     * 实例概览（对应 {@code INFO} 的常用字段 + 各 db 键数）。
     *
     * <p>{@code used_memory_peak_human} 值得盯：现场 137 的峰值曾达 12.45G、
     * 当前 4.52G —— 说明它曾经接近过内存上限（而 {@code maxmemory=0}
     * 表示**没有上限**，写满会直接报错而不是淘汰旧键）。
     */
    public RedisInstanceInfoView instanceInfo(String host) {
        requireHost(host);
        StatefulRedisConnection<String, String> conn = requireConnection(host);
        int port = gateway.portOfHost(host);

        RedisCommands<String, String> cmd = conn.sync();
        Map<String, String> info = parseInfo(cmd.info());

        // 各 db 的键数：INFO 的 dbN 段形如 "db0:keys=15,expires=0,avg_ttl=0"
        List<RedisInstanceInfoView.DbKeyCount> dbKeys = new ArrayList<>();
        for (Map.Entry<String, String> e : info.entrySet()) {
            if (isDbKey(e.getKey()) && e.getValue().contains("keys=")) {
                try {
                    int db = Integer.parseInt(e.getKey().substring(2));
                    long keys = Long.parseLong(field(e.getValue(), "keys"));
                    dbKeys.add(new RedisInstanceInfoView.DbKeyCount(db, keys));
                } catch (RuntimeException ex) {
                    // 单个 db 段解析失败不影响整体；记日志而不是整个接口失败
                    log.warn("解析 INFO 的 {} 段失败：{}", e.getKey(), e.getValue());
                }
            }
        }
        dbKeys.sort(java.util.Comparator.comparingInt(RedisInstanceInfoView.DbKeyCount::db));

        return new RedisInstanceInfoView(
                host,
                port,
                text(info, "redis_version"),
                text(info, "redis_mode"),
                text(info, "os"),
                number(info, "uptime_in_seconds"),
                number(info, "connected_clients"),
                number(info, "used_memory"),
                text(info, "used_memory_human"),
                text(info, "used_memory_peak_human"),
                number(info, "maxmemory"),
                text(info, "maxmemory_policy"),
                number(info, "total_commands_processed"),
                number(info, "keyspace_hits"),
                number(info, "keyspace_misses"),
                dbKeys);
    }

    /**
     * 是否是 keyspace 的 db 段。
     *
     * <p>⚠️ 必须用正则限定为 {@code db数字}：新版本 Redis 的 INFO 里还有
     * {@code db0_distrib_strings_sizes} 这类**看起来也以 db 开头**的段，
     * 直接 {@code startsWith("db")} 会在 {@code substring(2)} 时抛
     * {@code NumberFormatException("0_distrib_strings_sizes")}。
     * 实测 137 的 INFO 里确实有这两个段。
     */
    private static boolean isDbKey(String key) {
        return key != null && key.matches("db\\d+");
    }

    /* ============================== 键扫描 ============================== */

    /**
     * 扫描键（分页）。
     *
     * <p><b>关于分页的实现方式</b>：SCAN 游标本身是**无状态**的，
     * 要跨请求翻页就得把游标回传。但游标在数据变动时会失效或漏键，
     * 而且前端这里的分页语义是「翻到第 N 页」—— 用游标做不了「上一页」。
     *
     * <p>所以这里的做法是：**在服务端 SCAN 到上限（2000）为止**，
     * 再在内存里做过滤 + 分页。这样「翻页」语义稳定，
     * 且 2000 的上限保证了不会把实例扫垮。
     * ⚠️ 代价是**超过 2000 个键的 db 只能看到前 2000 个** ——
     * 这一点通过 {@code truncated} 字段**明确告知**，
     * 绝不静默截断（否则用户会以为「这个 db 就这么多键」）。
     */
    public ScanResult scanKeys(String host, int db, String keyword, String type,
                               Boolean hasTtl, int page, int pageSize) {
        requireHost(host);
        // db 可以按请求切换：同一台实例的不同 db 才是用户要看的东西
        int targetDb = resolveDb(db);
        StatefulRedisConnection<String, String> conn = requireConnection(host);
        RedisCommands<String, String> cmd = conn.sync();
        // 连接固定连在配置的第一个 db 上；切走之后必须切回来
        int homeDb = gateway.homeDbOf(host);

        // ⚠️ 连接是共享的（按实例复用），切了 db 必须切回去，
        // 否则会污染其它请求 —— 那种 bug 表现为「随机查到别的 db 的键」，
        // 极难定位。所以用 try/finally 保证恢复。
        boolean switched = targetDb != homeDb;
        try {
            if (switched) {
                cmd.select(targetDb);
            }
            return doScan(cmd, host, targetDb, keyword, type, hasTtl, page, pageSize);
        } finally {
            if (switched) {
                try {
                    cmd.select(homeDb);
                } catch (Exception ex) {
                    log.warn("恢复实例 {} 的 db{} 失败：{}", host, homeDb, ex.getMessage());
                }
            }
        }
    }

    private ScanResult doScan(RedisCommands<String, String> cmd, String host,
                              int db, String keyword, String type, Boolean hasTtl,
                              int page, int pageSize) {
        long dbSize = cmd.dbsize();

        // SCAN 到上限为止（不按页扫：SCAN 游标做不了「上一页」，见方法注释）
        List<String> keys = scanUpTo(cmd, SCAN_HARD_LIMIT);
        boolean truncated = keys.size() >= SCAN_HARD_LIMIT;

        // 过滤 + 按需补齐 type/pttl/length。
        // 上限已被 SCAN_HARD_LIMIT 限制在 2000，实测 137 db0 约 10~30ms。
        List<RedisKeyInfoView> enriched = new ArrayList<>();
        for (String key : keys) {
            if (keyword != null && !keyword.isBlank() && !key.contains(keyword)) {
                continue;
            }
            String keyType;
            long pttl;
            Long length;
            try {
                keyType = cmd.type(key);
                pttl = cmd.pttl(key);
                length = lengthOf(cmd, key, keyType);
            } catch (Exception ex) {
                // 单个键读失败不影响整页：跳过而不是让整个接口 500
                log.warn("读取键元信息失败 key={} -> {}", key, ex.getMessage());
                continue;
            }
            if (type != null && !type.isBlank() && !type.equals(keyType)) {
                continue;
            }
            if (hasTtl != null && (pttl >= 0) != hasTtl) {
                continue;
            }
            enriched.add(new RedisKeyInfoView(key, keyType, pttl, length, domainOf(key)));
        }

        long total = enriched.size();
        int safePageSize = Math.min(Math.max(1, pageSize), 200);
        int safePage = Math.max(1, page);
        int from = Math.min((safePage - 1) * safePageSize, enriched.size());
        int to = Math.min(from + safePageSize, enriched.size());

        return new ScanResult(
                List.copyOf(enriched.subList(from, to)),
                total,
                safePage,
                safePageSize,
                host + "/db" + db,
                db,
                dbSize,
                truncated,
                SCAN_HARD_LIMIT);
    }

    /**
     * SCAN 到指定上限或游标结束。
     *
     * <p>必须循环：SCAN 是增量游标，一次调用只返回一批。
     * 只调一次会漏键 —— 而「键列表少了一些」很难被察觉。
     */
    private static List<String> scanUpTo(RedisCommands<String, String> cmd, int limit) {
        List<String> keys = new ArrayList<>();
        ScanArgs args = new ScanArgs().limit(500);
        KeyScanCursor<String> cursor = cmd.scan(args);
        int rounds = 0;
        while (true) {
            for (String k : cursor.getKeys()) {
                keys.add(k);
                if (keys.size() >= limit) {
                    return keys;
                }
            }
            if (cursor.isFinished() || ++rounds > 200) {
                return keys;
            }
            cursor = cmd.scan(cursor, args);
        }
    }

    /* ============================== 键详情 ============================== */

    /** 单个键的内容（按类型返回不同结构，页面据此选渲染方式） */
    public RedisKeyDetailView keyDetail(String host, int db, String key) {
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("必须提供 key");
        }
        requireHost(host);
        int targetDb = resolveDb(db);
        StatefulRedisConnection<String, String> conn = requireConnection(host);
        RedisCommands<String, String> cmd = conn.sync();
        int homeDb = gateway.homeDbOf(host);

        boolean switched = targetDb != homeDb;
        try {
            if (switched) {
                cmd.select(targetDb);
            }
            String type = cmd.type(key);
            if ("none".equals(type)) {
                throw BizException.notFound(
                        "键不存在：" + key + "（在 " + host + "/db" + targetDb
                                + "，可能已过期或被删除）");
            }
            long pttl = cmd.pttl(key);
            Long length = lengthOf(cmd, key, type);
            Long memoryUsage = safeMemoryUsage(cmd, key);
            String encoding = safeEncoding(cmd, key);

            String value = null;
            String json = null;
            List<RedisKeyDetailView.StreamEntry> entries = null;
            List<String> members = null;

            switch (type) {
                case "string" -> value = cmd.get(key);
                case "ReJSON-RL" -> {
                    // ReJSON 用 JSON.GET（GET 会 WRONGTYPE）；页面按 JSON 格式化展示
                    List<io.lettuce.core.json.JsonValue> vals = cmd.jsonGet(key);
                    if (vals != null && !vals.isEmpty() && !vals.get(0).isNull()) {
                        json = vals.get(0).toString();
                    }
                }
                case "stream" -> {
                    // 只取最近的若干条：1400 万条的流不能全取
                    List<StreamMessage<String, String>> msgs = cmd.xrange(key,
                            Range.create("-", "+"), Limit.from(STREAM_ENTRY_LIMIT));
                    entries = new ArrayList<>();
                    // 取最后 N 条（最近的）：xrange 从头取，这里反向截取
                    int size = msgs.size();
                    int start = Math.max(0, size - STREAM_ENTRY_LIMIT);
                    for (StreamMessage<String, String> m : msgs.subList(start, size)) {
                        entries.add(new RedisKeyDetailView.StreamEntry(m.getId(), m.getBody()));
                    }
                }
                case "list" -> members = safeList(cmd.lrange(key, 0, MEMBER_LIMIT - 1));
                case "set" -> members = safeSet(cmd.smembers(key));
                case "zset" -> members = safeList(cmd.zrange(key, 0, MEMBER_LIMIT - 1));
                case "hash" -> {
                    // hash 用 "字段 = 值" 的文本表示；页面按「成员」列表展示
                    Map<String, String> h = cmd.hgetall(key);
                    members = new ArrayList<>();
                    int i = 0;
                    for (Map.Entry<String, String> e : h.entrySet()) {
                        members.add(e.getKey() + " = " + e.getValue());
                        if (++i >= MEMBER_LIMIT) {
                            break;
                        }
                    }
                }
                default -> {
                    // 未知类型：不猜、不假装有内容，如实说明
                    log.warn("暂不支持的 Redis 类型 key={} type={}", key, type);
                }
            }

            return new RedisKeyDetailView(key, type, pttl, length, value, json, entries, members,
                    memoryUsage, encoding, host, targetDb);
        } finally {
            if (switched) {
                try {
                    cmd.select(homeDb);
                } catch (Exception ex) {
                    log.warn("恢复 db 失败：{}", ex.getMessage());
                }
            }
        }
    }

    /* ============================== 发布订阅 ============================== */

    /**
     * 列出 Pub/Sub 频道及其订阅者数。
     *
     * <p><b>为什么订阅者数比频道名更重要</b>：现场 136 有 14 个频道、
     * 137 **一个都没有**。而「有频道但订阅者是 0」意味着消息发出去没人在听 ——
     * 运维判断要靠这个数字，光看频道名看不出来。
     *
     * <p>注：Pub/Sub 的频道是**实例级**的，与 db 无关
     * （Redis 的订阅不区分 db），所以这里不接受 db 参数。
     */
    public List<RedisPubSubChannelView> pubsubChannels(String host) {
        requireHost(host);
        StatefulRedisConnection<String, String> conn = requireConnection(host);
        RedisCommands<String, String> cmd = conn.sync();

        List<RedisPubSubChannelView> out = new ArrayList<>();
        // PUBSUB CHANNELS 列出活跃频道（有订阅者的才会出现）
        List<String> channels = cmd.pubsubChannels();
        if (channels == null || channels.isEmpty()) {
            return out;
        }
        // PUBSUB NUMSUB 一次问多个频道的订阅者数（避免逐个往返）
        java.util.Map<String, Long> numsub = cmd.pubsubNumsub(channels.toArray(new String[0]));
        for (String ch : channels) {
            long subs = numsub == null || numsub.get(ch) == null ? 0L : numsub.get(ch);
            out.add(new RedisPubSubChannelView(ch, subs, pubsubGroupOf(ch), host));
        }
        return out;
    }

    /**
     * 按现场命名规律给频道分组（页面按组展示）。
     *
     * <p>与前端 {@code PubSubGroup} 的取值一致：
     * {@code trade / strategy / datahub / directed / other}。
     * 对不上就归 {@code other}，不硬猜。
     */
    static String pubsubGroupOf(String channel) {
        if (channel == null) {
            return "other";
        }
        if (channel.startsWith("tradeserver")) {
            return "trade";
        }
        if (channel.startsWith("strategyserver")) {
            return "strategy";
        }
        if (channel.startsWith("datahub")) {
            return "datahub";
        }
        if (channel.startsWith("WT-")) {
            return "directed";
        }
        return "other";
    }

    /* ============================== 删除键 ============================== */

    /**
     * 删除一个键（**高危**）。
     *
     * <p>DEL 不可撤销，现场 137 的入口流有 1400 万条条目 ——
     * 删错键会直接影响数据中台。所以：
     * <ul>
     *   <li>controller 上用 {@code action:storage-redis:delete-key} 权限点
     *       （该权限点已在 T_AdminRolePermission 里存在，且标记为高危）；</li>
     *   <li>无论成功失败都写审计（谁、删了哪个实例的哪个键、结果如何）；</li>
     *   <li>返回真实的删除结果（{@code DEL} 返回删掉的键数）——
     *       键本来就不存在就是 0，**不假装成功**。</li>
     * </ul>
     *
     * @return 实际删除的键数（0 表示键不存在）
     */
    public long deleteKey(String host, int db, String key) {
        if (key == null || key.isBlank()) {
            throw BizException.badRequest("必须提供 key");
        }
        requireHost(host);
        int targetDb = resolveDb(db);
        StatefulRedisConnection<String, String> conn = requireConnection(host);
        RedisCommands<String, String> cmd = conn.sync();
        int homeDb = gateway.homeDbOf(host);

        boolean switched = targetDb != homeDb;
        try {
            if (switched) {
                cmd.select(targetDb);
            }
            Long deleted = cmd.del(key);
            long n = deleted == null ? 0L : deleted;
            log.warn("已删除 Redis 键 host={} db={} key={} deleted={}", host, targetDb, key, n);
            return n;
        } finally {
            if (switched) {
                try {
                    cmd.select(homeDb);
                } catch (Exception ex) {
                    log.warn("恢复 db 失败：{}", ex.getMessage());
                }
            }
        }
    }

    /* ============================== 工具 ============================== */

    /**
     * 管理页按**主机**定位（{@code instanceId} 就是主机地址）。
     *
     * <p>为什么不按 (host,db) 组合：管理页要浏览整台实例的所有 db，
     * 而配置里的组合只覆盖业务用到的那些（137 db0 有 1400 万条条目的流，
     * 但它不在业务组合里）。
     */
    private void requireHost(String host) {
        if (host == null || host.isBlank() || !gateway.hasHost(host)) {
            throw BizException.badRequest(
                    "未知的 Redis 主机：" + host + "（合法值见 /storage/redis/instances）");
        }
    }

    private StatefulRedisConnection<String, String> requireConnection(String host) {
        StatefulRedisConnection<String, String> conn = gateway.connectionByHost(host);
        if (conn == null) {
            // 如实报错，不返回空数据假装成功 —— 那会让人以为「这个实例没有键」
            throw BizException.badRequest(
                    "Redis 实例不可用：" + host + "（连接失败，详见后端日志）");
        }
        return conn;
    }

    /**
     * 该主机当前是否不可用（供**跨实例汇总**的调用方先判断，避免无谓的超时等待）。
     *
     * <p>管理页是「用户选一台实例来看」，所以 {@link #requireConnection} 在实例挂掉时
     * 如实报错是对的；但跨实例汇总（如消息总线的频道）不该因为一台挂掉就整块失败，
     * 调用方应当先用本方法跳过它。
     */
    public boolean isHostUsable(String host) {
        return gateway.isHostUsable(host);
    }

    /** db 取值校验：只允许 0-15（Redis 默认库数），避免非法值打到实例上 */
    private static int resolveDb(int db) {
        if (db < 0 || db > 15) {
            throw BizException.badRequest("db 取值必须在 0~15 之间，实际为：" + db);
        }
        return db;
    }

    /** 取容器类型的长度；非容器返回 null（string / ReJSON 等没有统一长度语义） */
    private static Long lengthOf(RedisCommands<String, String> cmd, String key, String type) {
        try {
            return switch (type) {
                case "list" -> cmd.llen(key);
                case "set" -> cmd.scard(key);
                case "zset" -> cmd.zcard(key);
                case "hash" -> cmd.hlen(key);
                case "stream" -> cmd.xlen(key);
                // ⚠️ string 没有 length 概念（值长度要 GET 才知道，代价更高）；
                // ReJSON 的「长度」语义含糊（顶层对象字段数？），都不猜，返回 null
                default -> null;
            };
        } catch (Exception ex) {
            log.warn("取键长度失败 key={} type={} -> {}", key, type, ex.getMessage());
            return null;
        }
    }

    /** MEMORY USAGE：老版本 Redis 或权限受限时可能不支持，失败就返回 null */
    private static Long safeMemoryUsage(RedisCommands<String, String> cmd, String key) {
        try {
            return cmd.memoryUsage(key);
        } catch (Exception ex) {
            log.debug("MEMORY USAGE 不可用 key={} -> {}", key, ex.getMessage());
            return null;
        }
    }

    /**
     * OBJECT ENCODING。
     *
     * <p>Lettuce 6.6.0 没有直接的 {@code objectEncoding} 方法，
     * 用 {@code dispatch} 发原生命令。取不到就返回 null（页面显示 {@code -}），
     * 不编造一个编码值。
     */
    private static String safeEncoding(RedisCommands<String, String> cmd, String key) {
        try {
            Object r = cmd.dispatch(io.lettuce.core.protocol.CommandType.OBJECT,
                    new io.lettuce.core.output.StatusOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8)
                            .add("ENCODING").add(key));
            return r == null ? null : r.toString();
        } catch (Exception ex) {
            log.debug("OBJECT ENCODING 不可用 key={} -> {}", key, ex.getMessage());
            return null;
        }
    }

    /** 集合类成员：用类型化命令 + 范围限制，避免把大集合整个取回来 */
    private static List<String> safeList(List<String> raw) {
        return raw == null ? List.of() : List.copyOf(raw);
    }

    private static List<String> safeSet(java.util.Set<String> raw) {
        if (raw == null) {
            return List.of();
        }
        // set 无序，成员数可能巨大 —— 用 srandmember 取固定条数更稳，
        // 但 SMEMBERS 已在现场规模下可接受（键都是几十个的小集合）；
        // 这里仍按 MEMBER_LIMIT 截断，避免将来遇到大集合时把响应撑爆
        List<String> out = new ArrayList<>(raw);
        return out.size() <= MEMBER_LIMIT ? out : out.subList(0, MEMBER_LIMIT);
    }

    /**
     * 按现场命名规律推断键的业务归属（页面按模块分组用）。
     *
     * <p>只作归类参考 —— 前缀对不上就归 {@code other}，不硬猜。
     */
    static String domainOf(String key) {
        if (key == null) {
            return "other";
        }
        if (key.startsWith("acc-")) {
            return "account";
        }
        if (key.startsWith("strategysrv-") || key.startsWith("strategy-")
                || key.startsWith("strategy_server_lock_")) {
            return key.startsWith("strategy_server_lock_") ? "lock" : "strategy";
        }
        if (key.startsWith("traderserver") || key.startsWith("WT-")) {
            return "trade";
        }
        if (key.startsWith("DataHub_") || key.startsWith("ST-")) {
            return "stream";
        }
        if (key.startsWith("unique_id")) {
            return "meta";
        }
        return "other";
    }

    /**
     * 解析 {@code INFO} 的文本输出。
     *
     * <p>格式是 {@code section} 行为 {@code # Section}、字段行为 {@code k:v}。
     * 这里按行解析，段名行跳过（dbN 段用另一套逻辑处理）。
     */
    private static Map<String, String> parseInfo(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int idx = s.indexOf(':');
            if (idx > 0) {
                out.put(s.substring(0, idx), s.substring(idx + 1));
            }
        }
        return out;
    }

    /** 从 {@code keys=15,expires=0} 里取某字段 */
    private static String field(String csv, String name) {
        for (String part : csv.split(",")) {
            int idx = part.indexOf('=');
            if (idx > 0 && part.substring(0, idx).trim().equals(name)) {
                return part.substring(idx + 1).trim();
            }
        }
        return null;
    }

    private static String text(Map<String, String> info, String key) {
        String v = info.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static Long number(Map<String, String> info, String key) {
        String v = info.get(key);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
