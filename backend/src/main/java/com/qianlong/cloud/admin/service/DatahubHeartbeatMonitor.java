package com.qianlong.cloud.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.qianlong.cloud.admin.config.DatahubProperties;
import com.qianlong.cloud.admin.config.RedisProperties;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据中台心跳监听 —— 判定「某台 Redis 上还有没有中台在通信」。
 *
 * <h2>这套判定能回答什么、不能回答什么（务必先读）</h2>
 * <table border="1">
 *   <tr><th>问题</th><th>能否回答</th></tr>
 *   <tr><td>这台 Redis 上有没有中台在活动</td><td>✅ 能</td></tr>
 *   <tr><td>现场一共有几个中台</td><td>⚠️ 不能可靠回答（多个中台共用一台 Redis 时只看到「在发」）</td></tr>
 *   <tr><td>是<b>哪几台机器</b>上的中台</td><td>❌ 完全不能</td></tr>
 * </table>
 *
 * <p>原因是心跳报文里**没有身份**：实测只有 {@code {"level":2,"role":1}}，
 * 没有 id / ip / mac；而 Redis Pub/Sub 也**不携带发布者信息**。
 * 同理，{@code level} / {@code role} **不能当节点 ID**（多个中台可能发同一个值，
 * 那会造成静默串行/漏行）。所以本类只记录「哪个实例上收到过心跳」，
 * 不尝试把心跳归属到具体某台中台。
 *
 * <h2>订阅开销与「中台数量」无关</h2>
 * 每台 Redis 实例一条订阅连接。Pub/Sub 频道是实例级的、不按 db 隔离
 * （实测 136 的 db0 与 db1 收到的是同一份），所以 50 个中台也还是这一条连接。
 *
 * <h2>⚠️ 本类最重要的一条设计：观测不到 ≠ 离线</h2>
 * 订阅断开、实例连不上、或开关关掉时，状态是 {@link State#UNKNOWN}，
 * <b>不是 {@code OFFLINE}</b>。对一个专门用来看「活着吗」的页面，
 * 假的「全离线」比诚实的「未知」危险得多 —— 它会把「我们没在看」
 * 说成「它们都死了」。所以 {@link State} 是三态而不是布尔。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatahubHeartbeatMonitor {

    private final RedisJsonGateway gateway;
    private final DatahubProperties properties;
    private final RedisProperties redisProperties;

    /** 实例 host -> 最后一次收到心跳的时刻（epoch 秒） */
    private final Map<String, Long> lastBeatAt = new ConcurrentHashMap<>();

    /** 实例 host -> 累计收到的心跳条数（用于判断「是不是一直没动」） */
    private final Map<String, Long> beatCount = new ConcurrentHashMap<>();

    /** 实例 host -> 订阅是否在线（连接断开会置 false） */
    private final Map<String, Boolean> subscribed = new ConcurrentHashMap<>();

    /** 实例 host -> 订阅失败原因（如实告知前端） */
    private final Map<String, String> subscribeError = new ConcurrentHashMap<>();

    /** 已建立的订阅连接（关闭时用） */
    private final List<StatefulRedisPubSubConnection<String, String>> pubSubConnections = new ArrayList<>();

    /** 实际订阅的频道名 —— 页面显示它，才能确认「我们听的是对的那一套频道」 */
    private volatile List<String> listeningChannels = List.of();

    /**
     * 我们自己订阅的频道集合。
     *
     * <p>用于修正「观测者效应」：{@code PUBSUB CHANNELS} 只列**有订阅者的**频道，
     * 我们一订阅，那个频道就从「不存在」变成「存在 1 个订阅者」。
     * 所以频道接口要把我们自己这份订阅扣掉，否则：
     * <ul>
     *   <li>订阅者数会永远比真实多 1；</li>
     *   <li>更糟的是**凭空多出一个频道** —— 现场 137 原本 0 个频道，
     *       订阅后变成 2 个，看 Redis 管理页的人会以为那里真有个频道。</li>
     * </ul>
     */
    public java.util.Set<String> ownSubscriptions() {
        return new java.util.LinkedHashSet<>(listeningChannels);
    }

    /** 三态：在线 / 离线 / 未知（观测不到时必须是「未知」） */
    public enum State {
        ONLINE,
        OFFLINE,
        /** 没有观测能力：订阅关闭、连接断开、或该实例连不上 */
        UNKNOWN
    }

    /**
     * 建成实例清单并开始订阅。
     *
     * <p>订阅是**异步**的：本方法不等待第一条心跳。
     * 所以服务刚启动的头几秒，所有实例都会是 {@code UNKNOWN}
     * （而不是错误地显示成「离线」）—— 等心跳到达后自动转 {@code ONLINE}。
     */
    @PostConstruct
    public void start() {
        if (!properties.getHeartbeat().isEnabled()) {
            log.warn("数据中台心跳订阅已关闭（app.datahub.heartbeat.enabled=false），"
                    + "页面上的在线状态将显示为「未知」而不是「离线」");
            return;
        }
        listeningChannels = properties.getHeartbeat().resolvedChannels();
        for (String host : gateway.distinctHosts()) {
            subscribeHost(host);
        }
        log.info("数据中台心跳订阅已启动：实例={} 频道={}",
                gateway.distinctHosts(), listeningChannels);
    }

    private void subscribeHost(String host) {
        try {
            StatefulRedisPubSubConnection<String, String> conn = gateway.openPubSub(host);
            if (conn == null) {
                subscribed.put(host, false);
                subscribeError.put(host, "该实例没有可用连接");
                return;
            }
            conn.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    record(host);
                }
            });
            // ⚠️ subscribe 在 **sync() 命令接口**上，不在 connection 本身 ——
            // StatefulRedisPubSubConnection 只提供 sync()/async()/addListener()。
            // 写成 conn.subscribe(...) 会编译不过；而本项目的 Eclipse 编译器
            // 会「生成一个含 Unresolved compilation problem 的 class」，
            // 表现为 mvn compile 不报错、**启动时**才抛 java.lang.Error
            // （踩过一次，日志里是 "Unresolved compilation problem"）。
            conn.sync().subscribe(listeningChannels.toArray(new String[0]));
            pubSubConnections.add(conn);
            subscribed.put(host, true);
            // 刚订阅上还没有任何心跳，先不写 lastBeatAt —— 保持 UNKNOWN 直到真的收到
            log.info("已订阅 {} 的中台心跳频道 {}", host, listeningChannels);
        } catch (Exception ex) {
            subscribed.put(host, false);
            subscribeError.put(host, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            // 订阅失败不抛出：页面应能打开，并如实显示「未知 + 原因」
            log.error("订阅 {} 的中台心跳失败：{}", host, ex.getMessage());
        }
    }

    private void record(String host) {
        long now = Instant.now().getEpochSecond();
        lastBeatAt.put(host, now);
        beatCount.merge(host, 1L, Long::sum);
    }

    @PreDestroy
    public void stop() {
        for (StatefulRedisPubSubConnection<String, String> conn : pubSubConnections) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 退出中，失败无所谓
            }
        }
        pubSubConnections.clear();
    }

    /* ================================ 查询 ================================ */

    /**
     * 某实例当前的心跳状态。
     *
     * <p>判定顺序（**先看有没有观测能力，再看时间**）：
     * <ol>
     *   <li>订阅开关关掉 → UNKNOWN（我们没在看，不能说它离线）</li>
     *   <li>该实例订阅失败 → UNKNOWN（附带原因）</li>
     *   <li>从未收到过心跳 → UNKNOWN（服务刚起，可能只是还没等到）</li>
     *   <li>距上次心跳 &le; 阈值 → ONLINE，否则 OFFLINE</li>
     * </ol>
     */
    public State stateOf(String host) {
        if (!properties.getHeartbeat().isEnabled()) {
            return State.UNKNOWN;
        }
        if (Boolean.FALSE.equals(subscribed.get(host))) {
            return State.UNKNOWN;
        }
        Long last = lastBeatAt.get(host);
        if (last == null) {
            return State.UNKNOWN;
        }
        long age = Instant.now().getEpochSecond() - last;
        return age <= properties.getOfflineAfterSec() ? State.ONLINE : State.OFFLINE;
    }

    /** 距上次心跳的秒数；从未收到返回 {@code null}（不返回 0 冒充「刚刚」） */
    public Long ageSecOf(String host) {
        Long last = lastBeatAt.get(host);
        if (last == null) {
            return null;
        }
        return Math.max(0L, Instant.now().getEpochSecond() - last);
    }

    /** 该实例累计收到的心跳条数；0 表示一条都没收到 */
    public long beatCountOf(String host) {
        return beatCount.getOrDefault(host, 0L);
    }

    /** 订阅是否正常建立（false 时前端应提示「观测不可用」） */
    public boolean isSubscribed(String host) {
        return Boolean.TRUE.equals(subscribed.get(host));
    }

    /** 订阅失败原因（没有则返回 null） */
    public String errorOf(String host) {
        return subscribeError.get(host);
    }

    /** 是否开启了订阅（关掉时页面应显示「未知」而非「离线」） */
    public boolean isEnabled() {
        return properties.getHeartbeat().isEnabled();
    }

    /** 实际订阅的频道名 —— 页面显示它，才能确认「我们听的是对的那一套频道」 */
    public List<String> channels() {
        return listeningChannels;
    }

    /** 全部实例的状态快照（host -> 状态），供 meta 汇总 */
    public Map<String, State> snapshot() {
        Map<String, State> out = new LinkedHashMap<>();
        for (String host : gateway.distinctHosts()) {
            out.put(host, stateOf(host));
        }
        return out;
    }
}
