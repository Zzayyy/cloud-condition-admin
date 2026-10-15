package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.config.TradeServerProperties;
import com.qianlong.cloud.admin.service.RegistryArrayReader.RegistryEntry;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 委托服务器（WT 方向）的**只读** Redis 访问。
 *
 * <p>本模块**不做启停**（用户已明确：只看状态）。所以这里只有读方法，
 * 一个写命令都没有 —— 现场 Redis 里跑着数据中台的生产流与真实业务对象，
 * 而本页要读的 {@code traderserver} 键和 {@code acc-*} 恰好都是生产正在用的。
 *
 * <p>涉及两个不同结构的对象，<b>不能共用一套解析</b>：
 * <ul>
 *   <li>注册表键 {@code traderserver}：值是
 *       <b>外面还包了一层同名对象</b>的数组 ——
 *       {@code {"traderserver": [ {id, ip, mac, "last-active-time", lastActiveTimeLong, enable}, ... ]}}，
 *       而且**在多个实例上各存一份且内容不同**，必须逐实例读；</li>
 *   <li>{@code acc-*} 键里的 {@code traderserver} 字段：是**直接的对象**
 *       {@code {id, ip, lastactivetime, lastActiveTimeLong, enable}} ——
 *       注意字段名是 {@code lastactivetime}（全小写），与注册表的
 *       {@code last-active-time}（带横线）**不一样**。这是现场的真实差异，
 *       两边都要照原样读，不要「统一」成一个名字。</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TraderServerRepository {

    /** acc-* 键前缀 */
    private static final String ACC_PREFIX = "acc-";

    private final RedisJsonGateway gateway;
    private final TradeServerProperties tradeProperties;
    private final RedisProperties redisProperties;

    /** 当前不可用的实例（实例 id -> 原因），页面据此判断「列表是否可信」 */
    public Map<String, String> unavailableInstances() {
        return gateway.unavailableInstances();
    }

    public List<String> availableInstanceNames() {
        return gateway.availableInstanceNames();
    }

    /**
     * 读注册表：把**每个实例上**的 {@code traderserver} 数组成员摊平成一个列表。
     *
     * <p>摊平逻辑（跨实例全读、剥掉外层同名对象、记住「来源实例 + 下标」）
     * 与策略服务器共用 {@link RegistryArrayReader} —— 那两个键的结构完全一样，
     * 各写一遍迟早会在某一份里漏掉「必须记住下标」这类细节
     * （现场 {@code id} 会重复：136 db1 的 7 条里有 4 个 {@code id=0}，
     * 只有 {@code (实例, 下标)} 能唯一定位一行）。
     *
     * @return 摊平后的条目；注册表键一个实例上都没有时返回空列表
     */
    public List<RegistryEntry> readRegistry() {
        String key = tradeProperties.getRegistryKey();
        return RegistryArrayReader.flatten(gateway.findAllAcrossInstances(key), key);
    }

    /**
     * 读全部 {@code acc-*} 里的 {@code traderserver} 字段（账号 → 委托服务器 的分配关系）。
     *
     * <p>与账号页共用同一份扫描结果的定义，但本方法**只取分配字段**，
     * 不返回账号的其它字段 —— 本模块不负责账号列表。
     *
     * <p>注意 {@code acc-*} 的键在多个实例上也可能各有一份（现场
     * {@code acc-1000006_1_6} 在 136 db1 与 137 db1 都有），
     * 沿用网关既有的「按配置顺序保留第一份」口径 —— 与账号页保持一致，
     * 否则同一个账号在两张页面上会显示成分配到了不同的服务器。
     *
     * @return acc 键名 → 该键的 traderserver 分配 + 来源实例；没有该字段的键不出现
     */
    public Map<String, Assignment> readAssignments() {
        Map<String, Assignment> out = new LinkedHashMap<>();
        gateway.scanJson(ACC_PREFIX, null).forEach((accKey, value) -> {
            JsonNode node = value.json() == null ? null : value.json().get("traderserver");
            if (node == null || node.isNull() || !node.isObject()) {
                // 现场确有 acc 键没有这个字段 / 或 id=-1 表示未分配 —— 如实记为「未分配」，
                // 不伪造一个对象出来（那会让页面显示成「分配到了某台机器」）
                return;
            }
            out.putIfAbsent(accKey, new Assignment(
                    node,
                    // 账号唯一名 = 键名去掉 acc- 前缀（acc-300130000461_1_6 -> 300130000461_1_6）
                    accKey.startsWith(ACC_PREFIX) ? accKey.substring(ACC_PREFIX.length()) : accKey,
                    value.instance()));
        });
        return out;
    }

    /** 配置里声明的 (host, db) 实例数（meta 用，便于判断「是不是只读到了一个库」） */
    public int configuredInstanceCount() {
        return redisProperties.getInstances() == null ? 0 : redisProperties.getInstances().size();
    }

    /** 心跳频道名（拼好 suffix，供页面与排查展示） */
    public TradeServerProperties.Channels channels() {
        return tradeProperties.getChannels();
    }

    /* ================================ 返回结构 ================================ */

    /*
     * 注：注册表条目类型已上移到 RegistryArrayReader.RegistryEntry ——
     * 因为策略服务器的 strategy-srv 是**完全相同的结构**，两处共用一个记录类，
     * 免得再定义一份一模一样的嵌套类型（也避免两边字段漂移）。
     */

    /**
     * 一个账号的委托服务器分配。
     *
     * @param json           {@code acc-*} 里 {@code traderserver} 字段的原始 JSON
     * @param account        唯一账号（从键名推出来）
     * @param instance       来源实例
     */
    public record Assignment(JsonNode json, String account, RedisProperties.Instance instance) {

        public String instanceDisplay() {
            return instance == null ? null : instance.display();
        }
    }
}
