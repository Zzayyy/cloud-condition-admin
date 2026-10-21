package com.qianlong.cloud.admin.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.qianlong.cloud.admin.config.RedisProperties;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.json.DefaultJsonParser;
import io.lettuce.core.json.JsonParser;
import io.lettuce.core.json.JsonPath;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Stream 的写访问（只给条件单下发用）。
 *
 * <p>单独一个类，不往 {@link RedisJsonGateway} 里加写命令：那个类的类注释
 * 明确写着「本类只做 SCAN 与 JSON.GET，没有任何写命令」，那是它能一直保持
 * 只读的原因，也正因为如此，它不该被改成能写。
 *
 * <p>三条红线：
 * <ol>
 * <li>写命令只有两类：<b>Stream</b>（XADD）与 {@link #patchConditionalMode}。
 *     后者是<b>唯一</b>一处 JSON.SET，路径由方法内部构造，
 *     只能改 {@code $.Strategy[下标].mode} 与 {@code .modifyTime} ——
 *     <b>不接受外部传入键名或路径</b>，所以"顺手改个别的键"在这条路上做不到。
 *     没有 SET / DEL / 任意 JSON.SET。</li>
 * <li>不提供消费组写入：XREADGROUP 会抢走真平台的消息（同组是负载均衡不是广播）。</li>
 * <li>每次操作新建连接：XREAD BLOCK 会占住连接直到超时。</li>
 * </ol>
 */
@Slf4j
@Component
public class RedisStreamWriter {

    /** 把「原始 JSON 文本」包成 JsonValue —— 6.6.0 的 jsonSet 只收 JsonValue */
    private static final JsonParser JSON_PARSER = new DefaultJsonParser();

    private final RedisProperties properties;
    private final RedisJsonGateway gateway;

    public RedisStreamWriter(RedisProperties properties, RedisJsonGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    /**
     * 往流里追加一条报文。
     *
     * @param fields 字段（现场实测：{@code request_id} + {@code task}）
     * @return 新条目的 id（形如 {@code 1792462860448-0}）
     */
    public String xadd(String host, int db, String stream, Map<String, String> fields) {
        if (stream == null || stream.isBlank() || fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("流名与报文字段都不能为空");
        }
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, properties.getTimeoutMs()));
            String id = conn.sync().xadd(stream, fields);
            log.warn("已向 Redis 下发报文：host={} db={} stream={} entryId={} request_id={}",
                    host, db, stream, id, fields.get("request_id"));
            return id;
        } catch (Exception ex) {
            gateway.reportHostFailure(host, ex);
            throw new IllegalStateException("下发报文失败：" + ex.getMessage(), ex);
        } finally {
            close(conn, client);
        }
    }

    /**
     * 取流尾部若干条（用来「记下当前尾 id，等回执时从这里往后读」）。
     *
     * <p>用 XREVRANGE + 条数上限，<b>绝不</b>全量拉 ——
     * 现场 {@code DataHub_reply_stream} 有 14 万条。
     */
    public List<StreamEntry> tail(String host, int db, String stream, int count) {
        List<StreamEntry> out = new ArrayList<>();
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, properties.getTimeoutMs()));
            List<StreamMessage<String, String>> rows =
                    conn.sync().xrevrange(stream, Range.create("-", "+"), Limit.from(count));
            for (StreamMessage<String, String> row : rows) {
                out.add(new StreamEntry(row.getId(), row.getBody()));
            }
        } catch (Exception ex) {
            log.warn("读取流尾部失败（不致命）：host={} db={} stream={} -> {}",
                    host, db, stream, ex.getMessage());
        } finally {
            close(conn, client);
        }
        return out;
    }

    /**
     * 阻塞等待流上出现条目（从 {@code fromId} 之后）。
     *
     * <p><b>用普通 XREAD，不建消费组</b> —— 建组会抢走真平台的消息。
     *
     * @return 读到的条目；超时返回空列表
     */
    public List<StreamEntry> readAfter(String host, int db, String stream, String fromId, long blockMs) {
        List<StreamEntry> out = new ArrayList<>();
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, blockMs + 2_000L));
            XReadArgs args = XReadArgs.Builder.block(blockMs).count(100);
            List<StreamMessage<String, String>> rows =
                    conn.sync().xread(args, StreamOffset.from(stream, fromId));
            if (rows != null) {
                for (StreamMessage<String, String> row : rows) {
                    out.add(new StreamEntry(row.getId(), row.getBody()));
                }
            }
        } catch (Exception ex) {
            gateway.reportHostFailure(host, ex);
            throw new IllegalStateException("等待回执失败：" + ex.getMessage(), ex);
        } finally {
            close(conn, client);
        }
        return out;
    }

    /**
     * 取流上的消费组与消费者名（安全闸用）。
     *
     * <p><b>判据</b>：真策略平台的消费者名 = <b>流名本身</b>（如 {@code ST-0}）；
     * 本项目的 mock 用 {@code ST-N-w0} 这种带后缀的名字。
     */
    public List<ConsumerInfo> consumers(String host, int db, String stream) {
        List<ConsumerInfo> out = new ArrayList<>();
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, properties.getTimeoutMs()));
            List<Object> groups = conn.sync().xinfoGroups(stream);
            if (groups == null) {
                return out;
            }
            for (Object g : groups) {
                String groupName = strOf(g, "name");
                if (groupName == null) {
                    continue;
                }
                List<Object> consumers = conn.sync().xinfoConsumers(stream, groupName);
                if (consumers == null) {
                    continue;
                }
                for (Object c : consumers) {
                    out.add(new ConsumerInfo(groupName, strOf(c, "name"),
                            longOf(c, "idle"), longOf(c, "pending")));
                }
            }
        } catch (Exception ex) {
            log.warn("读取消费组失败：host={} db={} stream={} -> {}", host, db, stream, ex.getMessage());
        } finally {
            close(conn, client);
        }
        return out;
    }

    /** 流长度（只读，诊断用） */
    public long length(String host, int db, String stream) {
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, properties.getTimeoutMs()));
            Long n = conn.sync().xlen(stream);
            return n == null ? 0L : n;
        } catch (Exception ex) {
            return 0L;
        } finally {
            close(conn, client);
        }
    }

    /**
     * 改条件单元素的状态与修改时间（{@code $.Strategy[下标].mode} / {@code .modifyTime}）。
     *
     * <h2>⚠️ 为什么不接受外部传入的路径</h2>
     * 一旦开放"传路径"，这个方法就变成了"任意 ReJSON 写"——
     * 而 Redis 里坐着的都是生产数据（`acc-*` 账号、`strategy-srv` 节点表、千万级的流）。
     * 所以路径在方法内部拼，调用方只能给<b>键名 + 数组下标</b>。
     *
     * <h2>⚠️ 下标会漂移，必须先读后写再校验</h2>
     * {@code Strategy} 是数组，{@code JSON.SET} 只能按下标定位。
     * 并发插入/删除会让下标错位，所以调用方应当：
     * 先读数组按 {@code id} 定位下标 → 调本方法 → 再读一次确认该下标的 {@code id}
     * 仍是目标单号。校验不通过就当失败，不能默认成功。
     *
     * <h2>⚠️ 改 Redis 不等于"让条件单真的停了"</h2>
     * 这只是本地镜像同步。<b>真生效必须靠协议下发</b>——
     * 如果策略平台持有内存态，它会把这里的写入覆盖掉。
     */
    public void patchConditionalMode(String host, int db, String key, int index,
                                     int mode, String modifyTime) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("键名不能为空");
        }
        if (!key.startsWith("strategy-") || "strategy-srv".equals(key)) {
            throw new IllegalArgumentException("只允许改条件单键（strategy-*，且排除 strategy-srv）：" + key);
        }
        if (index < 0) {
            throw new IllegalArgumentException("数组下标必须 >= 0");
        }
        RedisClient client = null;
        StatefulRedisConnection<String, String> conn = null;
        try {
            conn = connect(client = newClient(host, db, properties.getTimeoutMs()));
            String modePath = "$.Strategy[" + index + "].mode";
            String timePath = "$.Strategy[" + index + "].modifyTime";
            /*
             * ⚠️ Lettuce 6.6.0（Spring Boot 3.5.16 的 BOM 版本）的 jsonSet
             * 只有 (K, JsonPath, JsonValue) 这一个重载 —— 7.x 才有收 String 的版本。
             * 所以这里要用 DefaultJsonParser 把「原始 JSON 文本」包成 JsonValue。
             * 数字直接写 "0"，字符串要自带双引号 —— createJsonValue 收的是 JSON 文本，
             * 不是 Java 字符串值。
             */
            conn.sync().jsonSet(key, new JsonPath(modePath),
                    JSON_PARSER.createJsonValue(String.valueOf(mode)));
            conn.sync().jsonSet(key, new JsonPath(timePath),
                    JSON_PARSER.createJsonValue("\"" + escape(modifyTime) + "\""));
            log.warn("已改 Redis 条件单状态（本地镜像）：host={} db={} key={} index={} mode={}",
                    host, db, key, index, mode);
        } catch (Exception ex) {
            gateway.reportHostFailure(host, ex);
            throw new IllegalStateException("改 Redis 条件单状态失败：" + ex.getMessage(), ex);
        } finally {
            close(conn, client);
        }
    }

    // ------------------------------------------------------------ 内部

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private RedisClient newClient(String host, int db, long timeoutMs) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(portOf(host))
                .withDatabase(db)
                .withTimeout(Duration.ofMillis(Math.max(1_000L, timeoutMs)));
        String password = passwordOf(host);
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password.toCharArray());
        }
        return RedisClient.create(builder.build());
    }

    private StatefulRedisConnection<String, String> connect(RedisClient client) {
        return client.connect(new StringCodec());
    }

    private void close(StatefulRedisConnection<String, String> conn, RedisClient client) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 关闭失败无所谓
            }
        }
        if (client != null) {
            try {
                client.shutdown();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    private int portOf(String host) {
        return properties.getInstances().stream()
                .filter(i -> i.getHost().equals(host))
                .mapToInt(i -> i.getPort())
                .findFirst()
                .orElse(6379);
    }

    private String passwordOf(String host) {
        return properties.getInstances().stream()
                .filter(i -> i.getHost().equals(host))
                .map(i -> i.getPassword())
                .filter(p -> p != null && !p.isEmpty())
                .findFirst()
                .orElse(null);
    }

    /** 从 XINFO 结果里取字段（兼容 List / Map 两种形状） */
    private static String strOf(Object node, String field) {
        if (node instanceof Map<?, ?> map) {
            Object v = map.get(field);
            return v == null ? null : String.valueOf(v);
        }
        if (node instanceof List<?> list) {
            for (int i = 0; i + 1 < list.size(); i += 2) {
                if (field.equals(String.valueOf(list.get(i)))) {
                    return String.valueOf(list.get(i + 1));
                }
            }
        }
        return null;
    }

    private static Long longOf(Object node, String field) {
        String s = strOf(node, field);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 一条流条目 */
    public record StreamEntry(String id, Map<String, String> fields) {
    }

    /** 一个消费者 */
    public record ConsumerInfo(String group, String name, Long idleMs, Long pending) {
    }
}
