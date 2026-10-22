package com.qianlong.cloud.admin.service;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.entity.ConditionalEntity;
import com.qianlong.cloud.admin.mapper.ConditionalMapper;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.RequiredArgsConstructor;

/**
 * 定位「要操作的那张条件单」—— 只服务**写路径**（状态变更）。
 *
 * <h2>⭐ 2026-10-22：从「全量扫 strategy-*」改成「按 Account 拼键名只读一个键」</h2>
 * 原来为了找一张单，要把所有实例所有 db 的 {@code strategy-*} 键全扫一遍、
 * 逐个解析 {@code Strategy} 数组。现场 6 个键、62 个元素，还得处理
 * "同一个 id 在 136 db1 和 137 db1 各存一份"的重复 —— 为了找一条而读全量。
 *
 * <p>现在读路径已经确认以 MySQL 为准，所以：
 * <pre>
 *   ① MySQL 按 id 查 → 拿到 Account（= UniqueAccount，如 010100011300_7_6）
 *   ② 拼出键名 strategy-&lt;Account&gt;
 *   ③ 只读这一个键，数出 id 对应的数组下标
 * </pre>
 * 一次 GET 就够，不用 SCAN，也不会跨实例重复。
 *
 * <h2>为什么写路径还必须读 Redis</h2>
 * 两件事在 MySQL 表里<b>没有</b>：
 * <ul>
 *   <li>{@code strategyServerID} —— 决定报文发到哪个策略平台（{@code ST-<编号>}）。
 *       主来源是 {@code acc-*.strategyserverid}（现场确认它就是中台分配的编号），
 *       元素自带的 {@code strategyServerID} 作兜底；</li>
 *   <li><b>数组下标</b> —— {@code JSON.SET} 只能按 {@code $.Strategy[下标]} 定位。</li>
 * </ul>
 * 其余（当前状态 {@code mode}、账号、条件单号）都从 MySQL 取，与列表同源。
 */
@Service
@RequiredArgsConstructor
public class ConditionalTargetLookup {

    /** {@code strategy-*} 前缀 */
    private static final String STRATEGY_PREFIX = "strategy-";

    /** {@code acc-*} 前缀 */
    private static final String ACC_PREFIX = "acc-";

    private final RedisJsonGateway gateway;
    private final ConditionalMapper conditionalMapper;

    /**
     * 定位一张条件单。
     *
     * @param id 条件单号
     * @return 定位结果；MySQL 里没有这张单时返回 {@code null}
     */
    public ConditionalTarget resolve(Long id) {
        if (id == null) {
            return null;
        }
        String ref = String.valueOf(id);

        // ---- ① MySQL 是读路径的权威：找不到就是不存在 ----
        ConditionalEntity entity = conditionalMapper.selectByIdIn("T_Conditional", id);
        if (entity == null) {
            entity = conditionalMapper.selectByIdIn("T_Conditional_his", id);
        }
        if (entity == null) {
            return null;
        }

        String uniqueAccount = entity.getAccount();
        String strategyKey = uniqueAccount == null || uniqueAccount.isBlank()
                ? null : STRATEGY_PREFIX + uniqueAccount;

        // ---- ② 只读这一个键，数出下标 ----
        int index = -1;
        String elementServerId = null;
        String rawStrategy = null;
        String patchHost = null;
        int patchDb = -1;
        Integer redisMode = null;
        if (strategyKey != null) {
            RedisJsonValue value = gateway.findOne(strategyKey);
            if (value != null) {
                patchHost = value.instance() == null ? null : value.instance().getHost();
                patchDb = value.instance() == null ? -1 : value.instance().getDb();
                JsonNode arr = value.json() == null ? null : value.json().get("Strategy");
                if (arr != null && arr.isArray()) {
                    for (int i = 0; i < arr.size(); i++) {
                        JsonNode item = arr.get(i);
                        if (item == null || !item.hasNonNull("id")) {
                            continue;
                        }
                        if (ref.equals(item.get("id").asText())) {
                            index = i;
                            elementServerId = text(item, "strategyServerID");
                            rawStrategy = text(item, "strategy");
                            JsonNode m = item.get("mode");
                            redisMode = m == null || m.isNull() ? null : m.asInt();
                            break;
                        }
                    }
                }
            }
        }

        // ---- ③ 策略服务器编号：acc-* 是主来源，元素自带作兜底 ----
        String serverId = accountServerId(uniqueAccount);
        if (!usable(serverId)) {
            serverId = elementServerId;
        }

        return new ConditionalTarget(
                ref,
                uniqueAccount,
                entity.getAccount() == null ? null : fAccountOf(entity.getAccount()),
                null,
                null,
                serverId,
                strategyKey,
                patchHost,
                patchDb,
                rawStrategy,
                entity.getMode(),
                redisMode,
                index);
    }

    /** 读 {@code acc-<uniqueAccount>.strategyserverid}（中台分配的编号） */
    private String accountServerId(String uniqueAccount) {
        if (uniqueAccount == null || uniqueAccount.isBlank()) {
            return null;
        }
        RedisJsonValue acc = gateway.findOne(ACC_PREFIX + uniqueAccount);
        if (acc == null || acc.json() == null) {
            return null;
        }
        return text(acc.json(), "strategyserverid");
    }

    private static boolean usable(String serverId) {
        return serverId != null && serverId.trim().matches("\\d+");
    }

    private static String fAccountOf(String uniqueAccount) {
        int idx = uniqueAccount.indexOf('_');
        return idx > 0 ? uniqueAccount.substring(0, idx) : uniqueAccount;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * 一张条件单的定位结果。
     *
     * @param ref            条件单号（协议里的 {@code Ref}）
     * @param uniqueAccount  拼接账号（{@code UniqueAccount} / {@code Unique_Account}）
     * @param fAccount       资金账号 / 客户号
     * @param accountType    账号类型（预留：报文里需要时再填，MySQL 表无此列）
     * @param accAtt         渠道（同上）
     * @param serverId       策略服务器编号（{@code null} 或不合法表示无需通知平台）
     * @param strategyKey    Redis 键名 {@code strategy-<Account>}
     * @param patchHost      该键所在的 Redis 主机（写 Redis 用；{@code null} = 未找到）
     * @param patchDb        该键所在的 db（{@code -1} = 未找到）
     * @param rawStrategy    原始报文（元素里的 {@code strategy}，排查用）
     * @param mode           当前状态（<b>取自 MySQL</b>，用于状态机判断，与列表同源）
     * @param redisMode      当前状态（<b>取自 Redis 元素</b>，用于写完回读校验
     *                       和回滚 —— 校验 Redis 的写入必须读 Redis，
     *                       读 MySQL 会因为"还没写"而永远判失败）
     * @param arrayIndex     在 {@code Strategy} 数组里的下标（{@code -1} = 未找到）
     */
    public record ConditionalTarget(
            String ref,
            String uniqueAccount,
            String fAccount,
            Integer accountType,
            Integer accAtt,
            String serverId,
            String strategyKey,
            String patchHost,
            int patchDb,
            String rawStrategy,
            Integer mode,
            Integer redisMode,
            int arrayIndex) {

        /** 能否在 Redis 里直接改（键、实例、下标都已知） */
        public boolean patchableInRedis() {
            return strategyKey != null && !strategyKey.isBlank()
                    && patchHost != null && arrayIndex >= 0;
        }

        /** 来源实例展示串，如 {@code 192.168.1.136/db1}；未知返回 {@code null} */
        public String instanceDisplay() {
            return patchHost == null ? null : patchHost + "/db" + patchDb;
        }

        /** 编号是否可用（{@code -1} 之类是"尚未分配"的哨兵值，不能当编号用） */
        public boolean hasUsableServerId() {
            if (serverId == null || serverId.isBlank()) {
                return false;
            }
            String v = serverId.trim();
            return v.matches("\\d+") && Integer.parseInt(v) >= 0;
        }
    }
}
