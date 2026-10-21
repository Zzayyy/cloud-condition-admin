package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalView;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.RequiredArgsConstructor;

/**
 * Redis {@code strategy-*} 键的只读访问（条件单模块的数据源）。
 *
 * <h2>⚠️ 为什么条件单页面改成读 Redis</h2>
 * 现场实况（2026-10-21 复核）：MySQL 的 {@code T_Conditional} / {@code T_Conditional_his}
 * <b>都是 0 行</b>，而 Redis 的 {@code strategy-*} 键里有 <b>60 条</b>真实条件单
 * （136 db1 四个键共 56 条 + 137 db1 两个键共 4 条）。
 * 只读 MySQL 的话页面永远是空的 —— 那不是"没有条件单"，是"没读对地方"。
 *
 * <p>本类只负责<b>把 Redis 的原始结构转成前端契约的形状</b>，
 * 合并、筛选、分页、口径都在 {@code ConditionalService}。
 *
 * <h2>⚠️ Redis 侧的字段名是<b>小驼峰</b>，与 MySQL 列名不同</h2>
 * <pre>
 *   Redis strategy-* 元素 : id useraccount account strategy addTime runTime closeTime
 *                           runCount beginTime endTime status flag description validity
 *                           modifyTime triggeredTime reason mode type name
 *                           strategyServerID syncflag procflag
 *   MySQL T_Conditional   : id Account UserAccount Strategy AddTime ... ModifyTime
 * </pre>
 * 所以必须<b>显式逐字段</b>映射 —— 不能靠"驼峰自动匹配"，那会把
 * {@code addTime} 映射成 {@code Addtime} 之类的错列。
 *
 * <h2>⚠️ {@code strategy} 字段有两个坑</h2>
 * <ol>
 *   <li>它是<b>对象</b>（MySQL 那列是 text），要序列化成 JSON 文本给前端；</li>
 *   <li>部分条目存的是 <b>Python repr</b>（单引号），<b>不是合法 JSON</b> ——
 *       例如 {@code "{'modify': {'Account': ...}}"}。这类必须<b>原样透传</b>，
 *       让前端展示原文；强行解析会得到空对象，反而把内容弄丢了。</li>
 * </ol>
 */
@Repository
@RequiredArgsConstructor
public class RedisConditionalRepository {

    /** {@code strategy-*} 前缀 */
    private static final String STRATEGY_PREFIX = "strategy-";

    /** {@code strategy-srv} 是策略服务器节点清单，不是条件单，必须排除 */
    private static final java.util.Set<String> EXCLUDE = java.util.Set.of("strategy-srv");

    private final RedisJsonGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 取出全部条件单（跨实例、跨 db）。
     *
     * <p><b>同一个单号可能在多个实例上各存一份</b>（现场确实如此：README 记过
     * 「有 4 条同一个 id 在两个实例上各存一份」）。这里按实例清单顺序
     * <b>保留第一次出现的</b>并记下份数 —— 实时态以先读到的为准，
     * 且份数会如实带回页面，不做静默去重。
     *
     * @return 条件单列表（字段已转成前端契约形状）
     */
    public List<ConditionalView> findAll() {
        List<ConditionalView> out = new ArrayList<>();
        Map<String, RedisJsonValue> scanned = gateway.scanJson(STRATEGY_PREFIX, EXCLUDE);

        // 第一遍：按单号统计"在几个实例上各存了一份"
        Map<String, Integer> copies = new java.util.HashMap<>();
        for (RedisJsonValue value : scanned.values()) {
            for (JsonNode item : items(value)) {
                copies.merge(item.get("id").asText(), 1, Integer::sum);
            }
        }

        // 第二遍：只保留每个单号【第一次出现】的那一份（顺序 = 实例清单顺序，可预测）
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (RedisJsonValue value : scanned.values()) {
            String uniqueAccount = value.key().startsWith(STRATEGY_PREFIX)
                    ? value.key().substring(STRATEGY_PREFIX.length())
                    : null;
            for (JsonNode item : items(value)) {
                String ref = item.get("id").asText();
                if (!taken.add(ref)) {
                    continue;
                }
                out.add(toView(item, uniqueAccount, value.instanceDisplay(),
                        copies.getOrDefault(ref, 1)));
            }
        }
        return out;
    }

    /** 取一个键里的条件单数组 */
    private static List<JsonNode> items(RedisJsonValue value) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode root = value.json();
        JsonNode arr = root == null ? null : root.get("Strategy");
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode item : arr) {
            if (item != null && item.hasNonNull("id")) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Redis 元素 → 前端契约形状。
     *
     * <p>字段名是<b>显式逐个</b>映射的：Redis 侧小驼峰、MySQL 侧大驼峰，
     * 靠驼峰自动匹配会出错列。
     */
    private ConditionalView toView(JsonNode item, String uniqueAccount, String instance, int copies) {
        return new ConditionalView(
                longOf(item, "id"),
                text(item, "account"),
                text(item, "useraccount"),
                strategyText(item),
                text(item, "addTime"),
                text(item, "runTime"),
                text(item, "closeTime"),
                intOf(item, "runCount"),
                text(item, "beginTime"),
                text(item, "endTime"),
                intOf(item, "status"),
                intOf(item, "flag"),
                text(item, "description"),
                intOf(item, "validity"),
                text(item, "modifyTime"),
                text(item, "triggeredTime"),
                text(item, "reason"),
                intOf(item, "mode"),
                intOf(item, "type"),
                text(item, "name"),
                "redis",
                instance,
                intOf(item, "strategyServerID"),
                copies);
    }

    /**
     * {@code strategy} 字段 → JSON 文本。
     *
     * <p>Redis 里它是<b>对象</b>；但部分条目存的是 <b>Python repr</b>（单引号），
     * 那不是合法 JSON —— 这类必须<b>原样透传</b>，前端展示原文即可。
     * 强行 {@code readTree} 会得到空对象，把内容弄丢。
     */
    private String strategyText(JsonNode item) {
        JsonNode v = item.get("strategy");
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception ex) {
            return v.toString();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isTextual() ? v.asText() : v.toString();
    }

    private static Integer intOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isInt() || v.isLong() ? v.asInt() : null;
    }

    private static Long longOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

