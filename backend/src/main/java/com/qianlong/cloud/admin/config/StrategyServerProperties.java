package com.qianlong.cloud.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 策略服务器（ST 方向）配置（{@code app.strategy.*}）。
 *
 * <h2>与委托服务器的异同</h2>
 * <b>相同：</b>注册表键 {@code strategy-srv} 的结构与 {@code traderserver} 一模一样：
 * <pre>
 *   { "strategy-srv": [ { id, ip, mac, usecount, enable,
 *                         "login-time", "last-active-time", lastActiveTimeLong } ] }
 * </pre>
 * 同样跨实例各存一份且内容不同，所以本模块复用
 * {@code RedisJsonGateway.findAllAcrossInstances} 跨实例全读。
 *
 * <h2>⚠️ 现场现状：策略服务器未开放</h2>
 * 实测（2026-10-15）：
 * <ol>
 *   <li><b>注册表不刷新。</b>盯 30 秒，内容完全不变
 *       （136 db0 那条停在今天 10:01，136 db1 那条停在 9/24）。</li>
 *   <li><b>心跳频道上没有心跳。</b>{@code strategyserver_beat_1} 频道确实存在，
 *       但订阅 25 秒收到 0 条（带后缀与不带后缀两种拼法都订过）。</li>
 * </ol>
 * 因此：
 * <ul>
 *   <li>心跳收不到是<b>预期现象</b>，所以本模块<b>不加常驻订阅</b> ——
 *       加了也收不到；更糟的是 Redis 的 {@code PUBSUB CHANNELS} 只列
 *       「有订阅者的频道」，我们一订阅就会凭空多出一个订阅者，
 *       把 Redis 管理页的数字弄脏（数据中台那边已经踩过这个坑）。</li>
 *   <li>「在线」只能按 {@code lastActiveTimeLong} 判定，结果必然接近 0 台。
 *       页面会额外显示「注册表已 N 秒未刷新」，
 *       否则 21 天前的时间戳会被读成「策略平台全挂了」，让人查错方向。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.strategy")
public class StrategyServerProperties {

    /**
     * 心跳阈值（秒）：{@code lastActiveTimeLong} 超过这个秒数没刷新即视为离线。
     *
     * <p>⚠️ 必须与前端 {@code SERVER_OFFLINE_AFTER_SEC} 保持一致，
     * 否则会出现「页面显示 1 台在线、汇总卡说 0 台」这种没法解释的偏差。
     * 接口把本值放在 meta 里回传，页面据此自检。
     */
    private int offlineAfterSec = 30;

    /** 注册表键名。现场是 {@code strategy-srv}（带横线）。 */
    private String registryKey = "strategy-srv";

    /**
     * 注册表「多久没变化就算陈旧」（秒）。
     *
     * <p>用于区分两种完全不同的事：
     * <ul>
     *   <li>「节点都离线了」—— 服务出问题，要去查；</li>
     *   <li>「这个注册表本身就没在更新」—— 策略服务器未开放，
     *       离线是必然结果，查节点没有意义。</li>
     * </ul>
     * 现场正是后者（策略服务器未开放），所以这个提示是必需的，
     * 否则看页面的人会误判成服务故障。
     *
     * <p>判定依据是注册表里**最新**的那条 {@code lastActiveTimeLong}
     * 距今多久 —— 只要还有一台在正常刷新心跳，注册表就不算陈旧。
     */
    private int staleRegistryAfterSec = 120;
}
