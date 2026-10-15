package com.qianlong.cloud.admin.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianlong.cloud.admin.config.RedisProperties;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis ReJSON 只读网关 —— 多实例、多 db 的连接与扫描。
 *
 * <p><b>为什么自建连接而不是用 {@code RedisTemplate}：</b>
 * 现场的数据分散在**多个实例的多个 db**
 * （acc-* 在 136 db0/db1 与 137 db1；strategy-* 同样跨这三个库），
 * 而 Spring 的 {@code spring.data.redis.*} 只能表达一个连接。
 * 用一份实例清单（{@code app.redis.instances}）分别为每个实例建连接，
 * 才能把「某个对象在 Redis 侧到底存不存在」查准 —— 少读一个库的表现是
 * 「查不到」，而那很容易被误读成「确实没有」。
 *
 * <p><b>只读：</b>本类只做 {@code SCAN} 与 {@code JSON.GET}，没有任何写命令。
 * 现场 Redis 里跑着数据中台的生产流与真实业务对象，误写的代价极高。
 *
 * <p><b>降级：</b>单个实例连不上不影响其它实例，失败记入 {@code unavailable}，
 * 由调用方如实告知前端「数据可能不完整」，而不是整体 500。
 */
@Slf4j
@Component
public class RedisJsonGateway {

    /** SCAN 循环次数上限：游标异常时兜底，避免接口被挂住 */
    private static final int MAX_SCAN_ROUNDS = 50;

    private final RedisProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个实例一个连接；key = 实例 id */
    private final Map<String, StatefulRedisConnection<String, String>> connections = new LinkedHashMap<>();

    /** 每个实例一个客户端（用于按需创建「钉在某个 db 上」的连接） */
    private final Map<String, RedisClient> clients = new LinkedHashMap<>();

    /**
     * 「钉在某个 (host, db) 上」的连接缓存。
     *
     * <p><b>为什么要单独缓存，而不是复用实例连接再 {@code SELECT} 切换：</b>
     * 实例连接是**全应用共享**的，而 {@code SELECT} 作用在整个连接上。
     * 两个请求并发时（A 切到 db1、B 切到 db0）会互相插队，
     * 表现就是「随机查到别的 db 的键」—— 这类 bug 极难定位
     * （README 里记过这条，本文件下面 {@code connectionByHost} 的旧用法就是这样）。
     * 这里改为「每个 db 一条自己的连接」，建立时 select 一次、此后不再切换，
     * 从根上取消这种竞态。
     *
     * <p>连接数 = 需要访问的 (host, db) 组合数，现场个位数，代价可忽略。
     */
    private final Map<String, StatefulRedisConnection<String, String>> dbConnections = new LinkedHashMap<>();

    /** 建立失败的 (host, db) —— 避免每次请求都重试同一个必然失败的连接 */
    private final java.util.Set<String> failedDbConnections = new java.util.LinkedHashSet<>();

    /** 连接失败的实例 id -> 失败原因（供接口如实告知前端） */
    private final Map<String, String> failures = new LinkedHashMap<>();

    /**
     * 实例级熔断：host -> 冷却截止时刻（epoch 毫秒）。
     *
     * <h2>⚠️ 为什么必须有这个（2026-10-15 现场故障）</h2>
     * 137 那台 Redis 在**后端启动之后**挂掉了（TCP 从「通」变成**超时**）。
     * 此时程序手里握着的是一条**陈旧连接**：每条命令都要等满
     * {@code timeout-ms}（3 秒）才失败。而统计一台实例要遍历 {@code db0~db15}
     * —— 每个 (host,db) 各建一次连接、各超时一次：
     * <pre>
     *   16 个 db × 3 秒 ≈ 48 秒   ← 实测 /datahub/nodes 用了 51 秒
     * </pre>
     * 于是「一台 Redis 挂掉」直接把整个总览页拖到一分钟以上，
     * 页面看起来就是「加载不出来」。
     *
     * <p>修法：**只要某台实例上有一个操作失败，就把整台主机标记为不可用**，
     * 冷却期内不再尝试它的任何 db —— 于是第一次失败仍要等 3 秒（无法避免，
     * 网络超时就是这样），但剩下 15 个 db 立刻跳过，整体从 48 秒降到 3 秒。
     *
     * <p>冷却结束会「半开」重试一次：现场恢复后不需要重启后端就能自动恢复。
     */
    private final Map<String, Long> hostCooldownUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** 熔断冷却时长（毫秒）。太短会频繁撞超时，太长则现场恢复后迟迟不显示 */
    private static final long HOST_COOLDOWN_MS = 30_000;

    public RedisJsonGateway(RedisProperties properties) {
        this.properties = properties;
        for (RedisProperties.Instance instance : properties.getInstances()) {
            /*
             * 同一台主机会有多条配置（136-db0 / 136-db1）。若它已经连不上，
             * 就别为它的每个 db 再各等一次超时 —— 启动时间会成倍变长。
             */
            if (failures.containsKey(instance.getHost())) {
                log.warn("跳过 {}（该主机已有连接失败记录）", instance.display());
                continue;
            }
            try {
                RedisURI.Builder builder = RedisURI.builder()
                        .withHost(instance.getHost())
                        .withPort(instance.getPort())
                        .withDatabase(instance.getDb())
                        .withTimeout(Duration.ofMillis(properties.getTimeoutMs()));
                if (instance.getPassword() != null && !instance.getPassword().isEmpty()) {
                    builder.withPassword(instance.getPassword().toCharArray());
                }
                RedisClient client = RedisClient.create(builder.build());
                StatefulRedisConnection<String, String> conn = client.connect(new StringCodec());
                conn.setTimeout(Duration.ofMillis(properties.getTimeoutMs()));
                connections.put(instance.getId(), conn);
                clients.put(instance.getId(), client);
                log.info("Redis 实例已连接：{}", instance.display());
            } catch (Exception ex) {
                /*
                 * ⚠️ 统一用 **host** 作为 `failures` 的键（不要用 instance.getId()）：
                 * 同一台主机会出现在多条配置里（136-db0 / 136-db1 是两个 id、
                 * 同一个 host）。用 id 当键的话，同一台挂掉的实例会以
                 * `137-1`、`136-0`、`136-1` 这种形式各出现一条，
                 * 而熔断标记用的是 host —— 于是前端会看到
                 * 「192.168.1.137 和 137-1 都不可用」这种同一件事写两遍的提示。
                 */
                failures.put(instance.getHost(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.error("Redis 实例连接失败：{} -> {}", instance.display(), ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void close() {
        dbConnections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关闭失败无所谓，进程都要退了
            }
        });
        dbConnections.clear();
        connections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关闭失败无所谓，进程都要退了
            }
        });
        connections.clear();
        clients.values().forEach(client -> {
            try {
                client.shutdown();
            } catch (Exception ignored) {
                // 同上
            }
        });
        clients.clear();
    }

    /**
     * 取一条**钉在指定 (host, db) 上**的连接（首次调用时建立并缓存）。
     *
     * <p>调用方**不需要**（也**不要**）再调 {@code SELECT} —— 连接已经在这个 db 上，
     * 反复切换正是上面注释里说的竞态来源。
     *
     * @return 连接；该主机连不上时返回 {@code null}（调用方须如实处理，不要假装成功）
     */
    public synchronized StatefulRedisConnection<String, String> connectionFor(String host, int db) {
        String key = host + "/db" + db;
        StatefulRedisConnection<String, String> cached = dbConnections.get(key);
        if (cached != null) {
            return cached;
        }
        /*
         * 实例级熔断：该主机刚失败过就直接跳过，不要再试它其余的 db。
         * 没有这一步的话，一台实例挂掉会让 16 个 db 各超时一次
         * （3 秒 × 16 ≈ 48 秒），整个页面卡死 —— 见 hostCooldownUntil 的说明。
         */
        if (isHostCoolingDown(host)) {
            return null;
        }
        /*
         * 建过的失败要记住，不要每次请求都重试一遍。
         * 典型场景：db 下标超范围（Redis 默认只有 db0~db15），
         * 每一轮统计都会试着连一次、再失败一次 —— 日志被同一条告警刷满，
         * 而且每次都要白等一个连接超时。
         */
        if (failedDbConnections.contains(key)) {
            return null;
        }
        RedisClient client = clientByHost(host);
        if (client == null) {
            return null;
        }
        try {
            StatefulRedisConnection<String, String> conn = client.connect(new StringCodec());
            conn.setTimeout(Duration.ofMillis(properties.getTimeoutMs()));
            // 只在建立时切一次；此后这条连接专属于这个 db
            conn.sync().select(db);
            dbConnections.put(key, conn);
            return conn;
        } catch (Exception ex) {
            failedDbConnections.add(key);
            // 整台主机进入冷却，避免剩下 15 个 db 继续各超时一次
            markHostFailed(host, ex);
            return null;
        }
    }

    /** 该主机是否处于熔断冷却期（过期的冷却记录顺手清掉，实现「半开」重试） */
    private boolean isHostCoolingDown(String host) {
        Long until = hostCooldownUntil.get(host);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            // 冷却结束：允许再试一次（现场恢复后无需重启后端）
            hostCooldownUntil.remove(host);
            failedDbConnections.removeIf(k -> k.startsWith(host + "/"));
            return false;
        }
        return true;
    }

    /** 标记整台主机失败（记原因 + 进入冷却） */
    private void markHostFailed(String host, Exception ex) {
        String reason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        failures.put(host, reason);
        hostCooldownUntil.put(host, System.currentTimeMillis() + HOST_COOLDOWN_MS);
        log.warn("Redis 实例 {} 不可用，{} 秒内跳过它的全部 db：{}",
                host, HOST_COOLDOWN_MS / 1000, reason);
    }

    /**
     * 报告某台实例上的命令失败了 —— 调用方捕获到异常时调它。
     *
     * <p>用途：连接可能是**建立成功**的（启动时 137 还活着），
     * 之后实例才挂掉。这种情况下 {@code connectionFor} 返回的是缓存里的
     * 陈旧连接，每次命令都会超时 —— 必须让调用方能把这次失败上报上来，
     * 触发整台主机的熔断，否则后面每个 db、每条命令都要继续等满超时。
     */
    public void reportHostFailure(String host, Exception ex) {
        markHostFailed(host, ex);
    }

    /** 该主机当前是否可用（供接口层在开始遍历前先判断，省掉第一次超时） */
    public boolean isHostUsable(String host) {
        return !isHostCoolingDown(host) && clientByHost(host) != null;
    }

    /** 该主机对应的客户端（按配置顺序取第一条） */
    private RedisClient clientByHost(String host) {
        for (RedisProperties.Instance i : properties.getInstances()) {
            if (i.getHost().equals(host) && clients.containsKey(i.getId())) {
                return clients.get(i.getId());
            }
        }
        return null;
    }

    /**
     * 为某台实例开一条 <b>Pub/Sub 专用</b>连接（心跳订阅用）。
     *
     * <p>Pub/Sub 不能用普通命令连接：一旦进入订阅模式，这条连接就只能收消息。
     * 所以单独开一条，由调用方负责 {@code close()}。
     *
     * <p>频道是**实例级**的（不按 db 隔离），所以这里不关心 db。
     */
    public StatefulRedisPubSubConnection<String, String> openPubSub(String host) {
        RedisClient client = clientByHost(host);
        if (client == null) {
            return null;
        }
        return client.connectPubSub(new StringCodec());
    }

    /** 当前不可用的实例（实例 id -> 原因） */
    public Map<String, String> unavailableInstances() {
        return Map.copyOf(failures);
    }

    /**
     * 按实例 id 取一个连接（Redis 管理页要用 INFO / SCAN / 类型查询等）。
     *
     * @return 连接；实例不可用时返回 {@code null}（调用方须处理，不要假装成功）
     */
    public StatefulRedisConnection<String, String> connection(String instanceId) {
        return connections.get(instanceId);
    }

    /** 实例配置（用于回显 host/port/db，以及校验 instanceId 是否合法） */
    public java.util.Optional<RedisProperties.Instance> instance(String instanceId) {
        return properties.getInstances().stream()
                .filter(i -> i.getId().equals(instanceId))
                .findFirst();
    }

    /** 全部实例配置（供 /storage/redis/instances 返回真实的实例清单） */
    public List<RedisProperties.Instance> allInstances() {
        return List.copyOf(properties.getInstances());
    }

    /**
     * 按**主机**取一个可用连接（Redis 管理页用）。
     *
     * <p><b>为什么管理页按主机而不是按 (host,db) 定位：</b>
     * 配置里的 {@code app.redis.instances} 是「业务需要读的 (host,db) 组合」
     * （acc-* 在 136 db0/db1、137 db1）。但管理页要**浏览整台实例的所有 db** ——
     * 比如 137 db0 有 117 个键（其中一个 stream 有 1400 万条条目），
     * 它不在业务清单里，却正是运维要看的东西。
     * 所以管理页用主机定位连接，具体 db 通过 SELECT 切换。
     *
     * @return 连接；该主机任何配置项都连不上时返回 {@code null}
     */
    public StatefulRedisConnection<String, String> connectionByHost(String host) {
        /*
         * 熔断中的主机视为不可用 —— 否则管理页点开一台挂掉的实例时，
         * INFO/SCAN 这些命令会各等一次超时，页面转很久才报错。
         */
        if (isHostCoolingDown(host)) {
            return null;
        }
        for (RedisProperties.Instance i : properties.getInstances()) {
            if (i.getHost().equals(host) && connections.containsKey(i.getId())) {
                return connections.get(i.getId());
            }
        }
        return null;
    }

    /** 该主机在配置里是否存在（用于校验管理页传来的 host） */
    public boolean hasHost(String host) {
        return properties.getInstances().stream().anyMatch(i -> i.getHost().equals(host));
    }

    /**
     * 去重后的主机清单（管理页的「实例」下拉用它）。
     *
     * <p>按配置里首次出现的顺序返回，保证顺序稳定可预测。
     */
    public List<String> distinctHosts() {
        List<String> out = new ArrayList<>();
        for (RedisProperties.Instance i : properties.getInstances()) {
            if (!out.contains(i.getHost())) {
                out.add(i.getHost());
            }
        }
        return out;
    }

    /** 某个主机的端口（配置里同主机端口应一致；取第一个） */
    public int portOfHost(String host) {
        return properties.getInstances().stream()
                .filter(i -> i.getHost().equals(host))
                .mapToInt(RedisProperties.Instance::getPort)
                .findFirst()
                .orElse(6379);
    }

    /**
     * 某个主机的「常驻 db」——配置里该主机第一条记录的 db。
     *
     * <p>连接建立后固定在某个 db 上。管理页切换 db 时必须能切回来，
     * 所以要记住这个常驻值；不知道它就会「切换后回不去」，
     * 表现为后续请求读到错的 db。
     */
    public int homeDbOf(String host) {
        return properties.getInstances().stream()
                .filter(i -> i.getHost().equals(host))
                .mapToInt(RedisProperties.Instance::getDb)
                .findFirst()
                .orElse(0);
    }

    /** 可用实例名（如 192.168.1.136/db0） */
    public List<String> availableInstanceNames() {
        List<String> names = new ArrayList<>();
        for (RedisProperties.Instance instance : properties.getInstances()) {
            if (connections.containsKey(instance.getId())) {
                names.add(instance.display());
            }
        }
        return names;
    }

    /**
     * 扫描全部实例，取出匹配前缀的 ReJSON 键。
     *
     * <p>同一个键可能**同时存在于多个实例/db**（现场确实如此：conditionals
     * 的 1000006_1_6 与 300130000461_1_6 在 136db1 与 137db1 都有）。
     * 这里按实例清单顺序**保留第一个**，不会静默丢数据 ——
     * 顺序由配置决定，可预测、可解释。
     *
     * @param prefix      键前缀，如 {@code acc-} / {@code strategy-}
     * @param excludeKeys 需要排除的完整键名（如 strategy-srv 这类非业务对象）
     */
    public Map<String, RedisJsonValue> scanJson(String prefix, java.util.Set<String> excludeKeys) {
        Map<String, RedisJsonValue> out = new LinkedHashMap<>();
        for (RedisProperties.Instance instance : properties.getInstances()) {
            StatefulRedisConnection<String, String> conn = connections.get(instance.getId());
            if (conn == null) {
                continue; // 连接失败，已在构造时记入 failures
            }
            // 该主机刚失败过就跳过，别让每条命令都等满一次超时
            if (isHostCoolingDown(instance.getHost())) {
                continue;
            }
            try {
                for (String key : scanKeys(conn, prefix)) {
                    if (excludeKeys != null && excludeKeys.contains(key)) {
                        continue;
                    }
                    try {
                        String raw = jsonGet(conn, key);
                        if (raw == null) {
                            continue;
                        }
                        out.putIfAbsent(key, new RedisJsonValue(
                                objectMapper.readTree(raw), instance, key, raw));
                    } catch (Exception ex) {
                        log.warn("读取 ReJSON 键失败 key={} instance={} -> {}", key, instance.display(), ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                // 上报失败 → 熔断整台主机（否则后面每个 db 都要再等一次超时）
                markHostFailed(instance.getHost(), ex);
                log.error("扫描 Redis 实例失败：{} -> {}", instance.display(), ex.getMessage());
            }
        }
        return out;
    }

    /** 取单个 ReJSON 键；键不存在返回 {@code null} */
    public RedisJsonValue findOne(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (RedisProperties.Instance instance : properties.getInstances()) {
            StatefulRedisConnection<String, String> conn = connections.get(instance.getId());
            if (conn == null) {
                continue;
            }
            if (isHostCoolingDown(instance.getHost())) {
                continue;
            }
            try {
                String raw = jsonGet(conn, key);
                if (raw != null) {
                    return new RedisJsonValue(objectMapper.readTree(raw), instance, key, raw);
                }
            } catch (Exception ex) {
                markHostFailed(instance.getHost(), ex);
                log.warn("读取 ReJSON 键失败 key={} instance={} -> {}", key, instance.display(), ex.getMessage());
            }
        }
        return null;
    }

    /**
     * 取**每个实例上的同一个键**，各自返回一份。
     *
     * <p><b>为什么不能用 {@link #findOne} 或 {@link #scanJson}：</b>
     * 那两者都按「键名」去重（{@code findOne} 命中第一个就返回、
     * {@code scanJson} 用 {@code putIfAbsent}），而现场有些键在多个实例上
     * **各存一份且内容不同**。最典型的是委托服务器注册表 {@code traderserver}：
     * <pre>
     *   136 db0  2 条   ← 测试残留
     *   136 db1  7 条   ← 唯一活跃的那台在这里
     *   137 db1  4 条   ← 全部 63 天前
     * </pre>
     * 用上面两个方法只能读到其中一份，表现是「节点列表少了几台」——
     * 而这恰好是运维最需要看见的信息（哪台还在、哪台早就死了）。
     *
     * <p>所以这里刻意**不去重**：有几个实例有这个键就返回几份，
     * 由调用方决定怎么合并与呈现。键不存在的实例直接跳过。
     *
     * @return 每个存在该键的实例一条，顺序与配置顺序一致
     */
    public List<RedisJsonValue> findAllAcrossInstances(String key) {
        List<RedisJsonValue> out = new ArrayList<>();
        if (key == null || key.isBlank()) {
            return out;
        }
        for (RedisProperties.Instance instance : properties.getInstances()) {
            StatefulRedisConnection<String, String> conn = connections.get(instance.getId());
            if (conn == null) {
                continue; // 连接失败，已在构造时记入 failures
            }
            if (isHostCoolingDown(instance.getHost())) {
                continue;
            }
            try {
                String raw = jsonGet(conn, key);
                if (raw == null) {
                    continue;
                }
                out.add(new RedisJsonValue(objectMapper.readTree(raw), instance, key, raw));
            } catch (Exception ex) {
                // 只读不到某一个实例不算整体失败，但要留痕且**熔断它** ——
                // 否则后面每个 db 都要再等一次超时
                markHostFailed(instance.getHost(), ex);
                log.warn("读取 ReJSON 键失败 key={} instance={} -> {}", key, instance.display(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * SCAN 到游标结束，取出全部匹配前缀的键。
     *
     * <p><b>必须循环</b>：SCAN 是增量游标，一次调用只返回一批（受 COUNT 影响），
     * 且**不保证一次返回全部**。只调一次会漏键 —— 表现是「某些对象查不到」，
     * 而这种缺失很难察觉（页面只是少了几行）。
     *
     * <p>用 SCAN 而不是 KEYS：现场 137 db0 有 400 万条流，KEYS 会把实例拖垮。
     */
    private List<String> scanKeys(StatefulRedisConnection<String, String> conn, String prefix) {
        List<String> keys = new ArrayList<>();
        ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(properties.getScanCount());
        KeyScanCursor<String> cursor = conn.sync().scan(args);
        int guard = 0;
        while (true) {
            keys.addAll(cursor.getKeys());
            if (cursor.isFinished()) {
                break;
            }
            if (++guard > MAX_SCAN_ROUNDS) {
                log.error("SCAN {}* 超过 {} 轮仍未结束，已提前中止（结果可能不完整）", prefix, MAX_SCAN_ROUNDS);
                break;
            }
            cursor = conn.sync().scan(cursor, args);
        }
        return keys;
    }

    /**
     * 取一个 ReJSON 键的原始 JSON 文本。
     *
     * <p><b>为什么不用 {@code GET}：</b>这些键的类型是 {@code ReJSON-RL}，
     * 字符串命令 {@code GET} 会直接报 WRONGTYPE，必须用 {@code JSON.GET}。
     *
     * <p><b>关于 API 版本：</b>Lettuce 6.6.0（Spring Boot 3.5.16 的 BOM 版本）
     * 提供的是 {@code jsonGet(K, JsonPath...)} 返回 {@code List<JsonValue>}，
     * 7.x 才有 {@code jsonGetRaw}。这里按 6.6.0 的签名写，
     * 用 {@code JsonValue.toString()} 取原始文本 —— <b>不要</b>用
     * {@code asString()}，那取的是 JSON 字符串字面量的**内容**（会剥掉结构）。
     */
    private static String jsonGet(StatefulRedisConnection<String, String> conn, String key) {
        List<io.lettuce.core.json.JsonValue> values = conn.sync().jsonGet(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        io.lettuce.core.json.JsonValue value = values.get(0);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.toString();
        return raw == null || raw.isBlank() || "null".equals(raw) ? null : raw;
    }

    /**
     * 一个 ReJSON 键的值。
     *
     * @param json     解析后的 JSON
     * @param instance 来源实例（页面/排查要知道「这条是从哪读到的」）
     * @param key      完整键名
     * @param rawJson  原始 JSON 文本（供原样展示，不做二次加工）
     */
    public record RedisJsonValue(JsonNode json, RedisProperties.Instance instance,
                                 String key, String rawJson) {

        public String instanceDisplay() {
            return instance == null ? null : instance.display();
        }
    }
}
