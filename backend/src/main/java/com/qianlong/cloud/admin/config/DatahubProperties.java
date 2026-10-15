package com.qianlong.cloud.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 数据中台（DataHub）配置（{@code app.datahub.*}）。
 *
 * <h2>为什么本模块没有「节点清单键」可读</h2>
 * 委托服务器那边有 Redis 键 {@code traderserver}，里面直接写着
 * {@code id / ip / mac / lastActiveTimeLong}，所以能一行行填表。
 * <b>中台没有这种键</b> —— 现场翻遍 136/137，只有
 * {@code DataHub_req_stream}、{@code DataHub_reply_stream} 与 {@code unique_id}，
 * 没有任何注册表。所以本模块的「节点」只能按 <b>Redis 实例</b>来分，
 * 页面上的每一行 = 一台 Redis 实例。
 *
 * <h2>⚠️ 心跳报文不含身份（实测 2026-10-14）</h2>
 * <pre>
 *   中台心跳     {"level":2,"role":1}                                 ← 无 id / ip / mac
 *   委托服务器心跳 {"id":1,"unique_string":"WT-...","ip":"...","mac":"..."}
 * </pre>
 * 因此：
 * <ul>
 *   <li><b>不能说</b>「136 这台机器上跑着中台」；只能说得出口的是
 *       「136 这台 Redis 上最近有中台在通信」。现场中台进程其实在 137 上，
 *       连的却是 136 的 Redis —— 这两件事必须分清。</li>
 *   <li>{@code level} / {@code role} 的语义**未在协议文档中定义**，且一台机器
 *       只能观察到一个取值（现场只有 role=1）。**绝不能当成节点 ID**：
 *       多个中台若发同一个 role，会造成静默串行 / 漏行，
 *       而那种错误在界面上看不出来。本模块不解析、不展示这两个字段的含义。</li>
 * </ul>
 *
 * <h2>在线判定的开销与中台数量无关</h2>
 * 判定方式是「在该实例上有没有收到心跳」，所以需要在每台实例上常驻一条
 * <b>订阅连接</b>。注意这个开销取决于<b>有几台 Redis</b>，与「有几个中台」无关 ——
 * Pub/Sub 的频道是实例级的、不按 db 隔离（实测 136 的 db0 与 db1 收到的是同一份），
 * 所以一台实例一条订阅即可，50 个中台也还是这一条。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.datahub")
public class DatahubProperties {

    /**
     * 心跳超时（秒）：超过这个时间没在该实例上收到心跳，即视为离线。
     *
     * <p>现场心跳间隔约 10 秒，30 秒 = 容忍连丢 2 次。
     */
    private int offlineAfterSec = 30;

    /**
     * 要统计的 db 上限（**含**该值）。
     *
     * <p>现场最多用到 db2；Redis 默认 16 个库，合法下标是 0~15 ——
     * 所以默认给 15。
     *
     * <p>⚠️ 别设成 16：db16 不存在，建立连接会抛
     * {@code ERR DB index is out of range}，而那个失败会在**每次请求**时重试一遍，
     * 日志里会刷满同样的告警（实测踩到过）。
     */
    private int maxDb = 15;

    /** 单次统计时每个 db 最多扫多少个键（XLEN 要逐键查，必须有上限） */
    private int scanLimit = 2000;

    private Heartbeat heartbeat = new Heartbeat();

    @Data
    public static class Heartbeat {

        /**
         * 是否常驻订阅心跳。
         *
         * <p>关掉之后页面会如实显示「未知」，<b>而不是</b>把没有观测
         * 当成「离线」——「没人在看」与「确实没在跑」是两件事。
         */
        private boolean enabled = true;

        /**
         * 判定用的频道名 —— **写实际在用的全名，不要靠拼后缀**。
         *
         * <p>⚠️ 这里有一个真实踩过的坑：早先为了「防后缀写错」，同时订阅了
         * 「基名」与「基名+suffix」两种拼法。结果**凭空造出了不带后缀的那个频道** ——
         * Redis 的 {@code PUBSUB CHANNELS} 只列「有订阅者的频道」，
         * 我们一订阅，它就从「不存在」变成「存在 1 个订阅者」，
         * 连 Redis 管理页上都能看到这个假频道。这是典型的
         * <b>观测行为改变了被观测对象</b>，比「听不到心跳」更糟。
         *
         * <p>现在的取舍：频道名写错 → 状态显示为「未知」（诚实、可查）；
         * 而不是造出一个假频道（会误导所有看 Redis 的人）。
         * 现场实测在用的是带 {@code _1} 后缀的那一套。
         */
        private List<String> channels = new ArrayList<>(List.of("datahub_beat_1"));

        /** 实际要订阅的频道名（去重、保序） */
        public List<String> resolvedChannels() {
            List<String> out = new ArrayList<>();
            for (String base : channels) {
                if (base == null || base.isBlank()) {
                    continue;
                }
                String trimmed = base.trim();
                if (!out.contains(trimmed)) {
                    out.add(trimmed);
                }
            }
            return out;
        }
    }
}
