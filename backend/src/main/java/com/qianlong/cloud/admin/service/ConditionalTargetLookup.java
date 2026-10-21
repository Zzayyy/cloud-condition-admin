package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.entity.ConditionalEntity;
import com.qianlong.cloud.admin.mapper.ConditionalMapper;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.RequiredArgsConstructor;

/**
 * 定位「要操作的那张条件单」以及「它该发到哪个策略平台」。
 *
 * <p>为什么主要读 Redis 而不是 MySQL：现场实况（2026-10-21 复核）MySQL 的
 * {@code T_Conditional} / {@code T_Conditional_his} 都是 0 行，而 Redis 的
 * {@code strategy-*} 键里有 56 条真实条件单。所以「这条单在哪」只能从 Redis 问，
 * MySQL 只作为兜底。
 *
 * <p>Redis 侧的字段名是<b>小驼峰</b>（{@code addTime} / {@code account}），
 * 且多出 {@code strategyServerID}、{@code syncflag}、{@code procflag} 三个字段
 * （MySQL 表里没有对应列），所以映射必须显式逐字段，不能靠驼峰自动匹配。
 *
 * <p>编号只能从条件单元素里取：{@code acc-*} 里的 {@code strategyserverid} 现场是
 * {@code -1} / {@code 0}，{@code -1} 是「还没分配到编号」的哨兵值，
 * 真正能用的是元素自带的 {@code strategyServerID}。
 */
@Service
@RequiredArgsConstructor
public class ConditionalTargetLookup {

    /** {@code strategy-*} 前缀 */
    private static final String STRATEGY_PREFIX = "strategy-";

    /** {@code strategy-srv} 是策略服务器节点清单，必须排除 */
    private static final Set<String> EXCLUDE = Set.of("strategy-srv");

    private final RedisJsonGateway gateway;
    private final ConditionalMapper conditionalMapper;

    /**
     * 定位一张条件单。
     *
     * @param id 条件单号
     * @return 定位结果；找不到返回 {@code null}
     */
    public ConditionalTarget resolve(Long id) {
        if (id == null) {
            return null;
        }
        String ref = String.valueOf(id);

        List<ConditionalTarget> found = new ArrayList<>();
        for (Map.Entry<String, RedisJsonValue> entry : gateway.scanJson(STRATEGY_PREFIX, EXCLUDE).entrySet()) {
            String key = entry.getKey();
            JsonNode root = entry.getValue().json();
            JsonNode arr = root == null ? null : root.get("Strategy");
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonNode item = arr.get(i);
                if (item == null || !item.hasNonNull("id")) {
                    continue;
                }
                if (!ref.equals(item.get("id").asText())) {
                    continue;
                }
                String uniqueAccount = key.startsWith(STRATEGY_PREFIX)
                        ? key.substring(STRATEGY_PREFIX.length())
                        : null;
                found.add(new ConditionalTarget(
                        ref,
                        uniqueAccount,
                        text(root, "account"),
                        intOf(root, "acctype"),
                        intOf(root, "maintype"),
                        text(item, "strategyServerID"),
                        key,
                        entry.getValue().instanceDisplay(),
                        text(item, "strategy"),
                        intOf(item, "mode"),
                        i,
                        1));
            }
        }

        if (!found.isEmpty()) {
            // 同一个单号在多个实例上各存一份是现场事实（README 记过），如实带回份数
            return found.get(0).withCopies(found.size());
        }

        // Redis 里没有 → 退到 MySQL（此时只有账号，没有编号）
        ConditionalEntity entity = conditionalMapper.selectByIdIn("T_Conditional", id);
        if (entity == null) {
            entity = conditionalMapper.selectByIdIn("T_Conditional_his", id);
        }
        if (entity == null) {
            return null;
        }
        String account = entity.getAccount();
        String fAccount = account;
        if (account != null && account.contains("_")) {
            fAccount = account.substring(0, account.indexOf('_'));
        }
        return new ConditionalTarget(
                ref,
                account,
                fAccount,
                null,
                null,
                null,
                account == null ? null : STRATEGY_PREFIX + account,
                "MySQL",
                entity.getStrategy(),
                entity.getMode(),
                -1,
                1);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    /**
     * 一张条件单的定位结果。
     *
     * @param ref            条件单号（= id 的字符串，协议里的 {@code Ref}）
     * @param uniqueAccount  拼接账号（协议里的 {@code UniqueAccount} / {@code Unique_Account}）
     * @param fAccount       资金账号 / 客户号
     * @param accountType    账号类型（1 资金账号 / 7 客户号）
     * @param accAtt         渠道（0 股票 / 6 期权）
     * @param serverId       策略服务器编号（<b>可能为空</b>）
     * @param sourceKey      来源 Redis 键（{@code strategy-<uniqueAccount>}）
     * @param sourceInstance 来源实例（{@code 192.168.1.136/db1}）
     * @param rawStrategy    原始报文（{@code strategy} 字段，排查用）
     * @param mode           当前状态（0 停止 / 1 运行 / 2 失效 / 3 结束），回滚时要用
     * @param arrayIndex     在 {@code Strategy} 数组里的下标（{@code -1} = 不在 Redis 里）
     * @param copies         同一个单号在几个实例上各存了一份
     */
    public record ConditionalTarget(
            String ref,
            String uniqueAccount,
            String fAccount,
            Integer accountType,
            Integer accAtt,
            String serverId,
            String sourceKey,
            String sourceInstance,
            String rawStrategy,
            Integer mode,
            int arrayIndex,
            int copies) {

        public ConditionalTarget withCopies(int n) {
            return new ConditionalTarget(ref, uniqueAccount, fAccount, accountType, accAtt,
                    serverId, sourceKey, sourceInstance, rawStrategy, mode, arrayIndex, n);
        }

        /** 是否能在 Redis 里直接改（键与下标都已知） */
        public boolean patchableInRedis() {
            return sourceKey != null && !sourceKey.isBlank() && arrayIndex >= 0;
        }

        /**
         * 编号是否可用。
         *
         * <p>现场 {@code acc-*.strategyserverid} 是 {@code -1} / {@code 0}，
         * {@code -1} 是「尚未分配」的哨兵值；{@code 0} 会打到 {@code ST-0} ——
         * 那恰好是现场真策略平台所在的流，所以也不能由系统自行默认。
         */
        public boolean hasUsableServerId() {
            if (serverId == null || serverId.isBlank()) {
                return false;
            }
            String v = serverId.trim();
            if (!v.matches("-?\\d+")) {
                return false;
            }
            return Integer.parseInt(v) >= 0;
        }
    }
}
