package com.qianlong.cloud.admin.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianlong.cloud.admin.config.RedisProperties;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis {@code acc-*} 键的只读访问。
 *
 * <p><b>为什么自建连接而不是用 {@code RedisTemplate}：</b>
 * acc-* 键分散在**多个实例的多个 db**（现场实测 136 db0/db1、137 db1），
 * 而 Spring 的 {@code spring.data.redis.*} 只能配置一个连接。
 * 用一份「实例清单」（{@code app.redis.instances}）分别为每个实例建连接，
 * 才能把「账号在 Redis 侧是否真的存在」这件事查准 ——
 * 少读一个库的表现是「某些账号 hasRedis=false」，
 * 那不是「没有」，而是「没查到」，两者在运维判断上完全不同。
 *
 * <p><b>只读：</b>本类只做 {@code SCAN} 与 {@code JSON.GET}，没有任何写命令。
 * 现场 Redis 里跑着数据中台的 400 万条流，误写的代价极高。
 *
 * <p><b>降级：</b>任一实例连不上时，记 error 日志并把该实例标记为不可用，
 * 接口继续返回 MySQL 侧数据 + 一个「Redis 有多少实例不可用」的提示，
 * 而不是整体 500 —— 账号页的 MySQL 部分仍然是有价值的信息。
 */
@Slf4j
@Repository
public class RedisAccountRepository {

    /** acc-* 键前缀（照现场命名规律） */
    private static final String ACC_PREFIX = "acc-";

    /** SCAN 循环次数上限：游标异常时兜底，避免接口被挂住 */
    private static final int MAX_SCAN_ROUNDS = 50;

    private final RedisProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个实例一个连接；key = 实例 id */
    private final Map<String, StatefulRedisConnection<String, String>> connections = new LinkedHashMap<>();

    /** 连接失败的实例 id -> 失败原因（供接口如实告知前端） */
    private final Map<String, String> failures = new LinkedHashMap<>();

    public RedisAccountRepository(RedisProperties properties) {
        this.properties = properties;
        for (RedisProperties.Instance instance : properties.getInstances()) {
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
                StatefulRedisConnection<String, String> conn =
                        client.connect(new StringCodec());
                conn.setTimeout(Duration.ofMillis(properties.getTimeoutMs()));
                connections.put(instance.getId(), conn);
                log.info("Redis 实例已连接：{}", instance.display());
            } catch (Exception ex) {
                failures.put(instance.getId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.error("Redis 实例连接失败：{} -> {}", instance.display(), ex.getMessage());
            }
        }
    }

    @PreDestroy
    public void close() {
        connections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关闭失败无所谓，进程都要退了
            }
        });
        connections.clear();
    }

    /** 当前不可用的实例（实例 id -> 原因），供接口如实告知 */
    public Map<String, String> unavailableInstances() {
        return Map.copyOf(failures);
    }

    /**
     * 扫描全部实例，取出 acc-* 键的 JSON 内容。
     *
     * @return key（即 acc-xxx_类型_渠道）-> 该键的 JSON 解析结果 + 来源实例
     */
    public Map<String, RedisAccountValue> findAll() {
        Map<String, RedisAccountValue> out = new LinkedHashMap<>();
        for (RedisProperties.Instance instance : properties.getInstances()) {
            StatefulRedisConnection<String, String> conn = connections.get(instance.getId());
            if (conn == null) {
                continue; // 连接失败，已在构造时记入 failures
            }
            try {
                for (String key : scanAccKeys(conn)) {
                    try {
                        String raw = jsonGet(conn, key);
                        if (raw == null) {
                            continue;
                        }
                        JsonNode node = objectMapper.readTree(raw);
                        // 同一个 account 可能同时存在于多个实例/db；
                        // 保留第一个（清单顺序即优先级），并记录来源便于排查
                        out.putIfAbsent(key, new RedisAccountValue(node, instance, key, raw));
                    } catch (Exception ex) {
                        log.warn("读取 acc 键失败 key={} instance={} -> {}", key, instance.display(), ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                failures.put(instance.getId(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
                log.error("扫描 Redis 实例失败：{} -> {}", instance.display(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * SCAN 到游标结束，取出全部 acc-* 键。
     *
     * <p><b>必须循环</b>：SCAN 是增量游标，一次调用只返回一批（受 COUNT 影响），
     * 且**不保证一次返回全部**。只调一次会漏键 —— 表现是「某些账号 hasRedis=false」，
     * 而这种缺失很难察觉（页面只是少了一个实时态标记）。
     *
     * <p>用 SCAN 而不是 KEYS：现场 137 db0 有 400 万条流，KEYS 会把实例拖垮。
     * 另外这里**加了循环次数上限**做保险，避免游标异常时把接口挂住。
     */
    private List<String> scanAccKeys(StatefulRedisConnection<String, String> conn) {
        List<String> keys = new ArrayList<>();
        ScanArgs args = ScanArgs.Builder.matches(ACC_PREFIX + "*").limit(properties.getScanCount());
        KeyScanCursor<String> cursor = conn.sync().scan(args);
        int guard = 0;
        while (true) {
            keys.addAll(cursor.getKeys());
            if (cursor.isFinished()) {
                break;
            }
            if (++guard > MAX_SCAN_ROUNDS) {
                log.error("SCAN acc-* 超过 {} 轮仍未结束，已提前中止（结果可能不完整）", MAX_SCAN_ROUNDS);
                break;
            }
            cursor = conn.sync().scan(cursor, args);
        }
        return keys;
    }

    /** 取单个 acc 键（详情接口用）。找不到返回 null。 */
    public RedisAccountValue findOne(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return null;
        }
        String key = accountKey.startsWith(ACC_PREFIX) ? accountKey : ACC_PREFIX + accountKey;
        for (RedisProperties.Instance instance : properties.getInstances()) {
            StatefulRedisConnection<String, String> conn = connections.get(instance.getId());
            if (conn == null) {
                continue;
            }
            try {
                String raw = jsonGet(conn, key);
                if (raw == null) {
                    continue;
                }
                return new RedisAccountValue(objectMapper.readTree(raw), instance, key, raw);
            } catch (Exception ex) {
                log.warn("读取 acc 键失败 key={} instance={} -> {}", key, instance.display(), ex.getMessage());
            }
        }
        return null;
    }

    /**
     * 取一个 ReJSON 键的原始 JSON 文本。
     *
     * <p><b>为什么不用 {@code GET}：</b>acc-* 的类型是 {@code ReJSON-RL}，
     * 字符串命令 {@code GET} 会直接报 WRONGTYPE。必须用 {@code JSON.GET}。
     *
     * <p><b>关于 API 版本：</b>Lettuce 6.6.0（Spring Boot 3.5.16 的 BOM 版本）
     * 提供的是 {@code jsonGet(K, JsonPath...)} 返回 {@code List<JsonValue>}，
     * 而 7.x 才有 {@code jsonGetRaw}。这里按 6.6.0 的签名写，
     * 并用 {@code JsonValue.toString()} 取原始文本 —— <b>不要</b>用
     * {@code asString()}，那取的是 JSON 字符串字面量的**内容**（会剥掉引号与结构）。
     *
     * @return 原始 JSON；键不存在或值为 null 时返回 {@code null}
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

    /** 可用实例数（用于页面提示「Redis 侧数据是否完整」） */
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
     * 一个 acc-* 键的值。
     *
     * @param json     解析后的 JSON（字段名照现场：useraccount/accounttype/maintype/onlineflag/...）
     * @param instance 来源实例（页面要显示「这条实时态是从哪读到的」）
     * @param key      完整键名
     * @param rawJson  原始 JSON 文本（页面「原始内容」tab 要原样展示，不做二次加工）
     */
    public record RedisAccountValue(JsonNode json, RedisProperties.Instance instance,
                                    String key, String rawJson) {

        public String instanceDisplay() {
            return instance == null ? null : instance.display();
        }
    }
}
