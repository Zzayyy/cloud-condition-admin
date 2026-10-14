package com.qianlong.cloud.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 委托服务器（WT 方向）配置（{@code app.trade.*}）。
 *
 * <p>放进 yml 而不是写死在代码里，是因为「有几台委托服务器、注册表在哪个库」
 * 本来就属于现场要改的东西：将来加一台、或者换一个 db，只改配置即可。
 *
 * <p><b>注册表的结构</b>（现场实测）：键 {@code traderserver} 的值是
 * <pre>
 *   { "traderserver": [ { id, ip, mac, "last-active-time", lastActiveTimeLong, enable }, ... ] }
 * </pre>
 * <b>外面还包了一层同名对象，数组在里面</b> —— 与 {@code acc-*} 键里的
 * {@code traderserver} 字段（那是一个直接的对象）结构不同，解析时不能共用一套。
 *
 * <p><b>为什么是「实例清单」而不是单个 host：</b>与 {@code acc-*} 一样，
 * 这个键在多台实例/多个 db 上**各存一份且内容不同**。实测 2026-10-14：
 * <pre>
 *   192.168.1.136 db0   2 条（测试残留）
 *   192.168.1.136 db1   7 条（id=1/ip=137 每 10 秒刷新，是唯一活跃的那台）
 *   192.168.1.137 db1   4 条（全部 63 天前）
 * </pre>
 * 只读一个库会漏节点；按 id 去重又会把现场真实存在的重复记录藏起来。
 * 所以：跨实例全读，重复项如实呈现（前端用「来源实例 + 数组下标」做行的唯一键）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.trade")
public class TradeServerProperties {

    /**
     * 心跳阈值（秒）：{@code lastActiveTimeLong} 超过这个秒数没刷新即视为离线。
     *
     * <p>⚠️ 必须与前端 {@code SERVER_OFFLINE_AFTER_SEC} 保持一致 ——
     * 后端 meta 里的 {@code onlineCount} 与页面上那颗「在线」标签是同一个判断的
     * 两个实现，阈值不一致会出现「页面上 1 台在线、汇总说 0 台」这种没法解释的偏差。
     * 接口把本值放在 meta 里回传，页面可据此自检。
     */
    private int offlineAfterSec = 30;

    /** 注册表键名。现场就是 {@code traderserver}（单数，不带前缀）。 */
    private String registryKey = "traderserver";

    /**
     * 读注册表的实例（{@code app.redis.instances} 的 id）。
     *
     * <p>**留空表示扫描全部已配置实例** —— 将来新增委托服务器时不必回来改这里，
     * 只要那个 (host,db) 在 {@code app.redis.instances} 里就能被读到。
     */
    private List<String> registryInstances = new ArrayList<>();

    private Channels channels = new Channels();

    /**
     * 心跳相关频道 —— <b>当前仅用于展示与排查</b>。
     *
     * <p>在线状态由 {@code lastActiveTimeLong} 推导，后端**没有**常驻订阅 Pub/Sub：
     * 订阅要在后台进程里长期占一条连接，而状态从键里已经能算准，
     * 为一个只用来展示的字段挂一条常驻订阅不划算。
     * 将来若要做「实时上下线告警」，再在这里加开关。
     *
     * <p>⚠️ 现场实测带 {@code _1} 后缀（{@code REDISSELECT=1}），
     * 不带后缀的那一套**不存在**：频道名是 `<名字><suffix>` 拼出来的。
     */
    @Data
    public static class Channels {
        private String online = "tradeserver_online";
        private String beat = "tradeserver_beat";
        private String offline = "tradeserver_offline";
        /** 现场是 {@code _<REDISSELECT>}，实测为 {@code _1} */
        private String suffix = "_1";

        /** 实际频道名（拼上 suffix） */
        public String onlineChannel() {
            return online + (suffix == null ? "" : suffix);
        }

        public String beatChannel() {
            return beat + (suffix == null ? "" : suffix);
        }

        public String offlineChannel() {
            return offline + (suffix == null ? "" : suffix);
        }
    }
}
