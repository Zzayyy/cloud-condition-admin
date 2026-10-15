package com.qianlong.cloud.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.extern.slf4j.Slf4j;

/**
 * 「注册表数组」读取的公共逻辑。
 *
 * <p>现场有三处这种结构（值 = <b>外面包一层同名对象</b>、数组在里面）：
 * <pre>
 *   traderserver   { "traderserver":  [ {...}, ... ] }   委托服务器
 *   strategy-srv   { "strategy-srv":  [ {...}, ... ] }   策略服务器
 * </pre>
 * 它们的字段几乎一样（{@code id / ip / mac / enable / last-active-time /
 * lastActiveTimeLong}，策略那边多一个 {@code usecount} 与 {@code login-time}）。
 *
 * <p>抽出来是因为这段逻辑有两个<b>容易写错又不容易发现</b>的点：
 * <ol>
 *   <li><b>必须跨实例全读。</b>这些键在多个实例上各存一份且内容不同
 *       （按配置顺序保留第一份的 {@code scanJson}/{@code findOne} 会漏节点）；</li>
 *   <li><b>必须记住「来源实例 + 数组下标」。</b>{@code id} 会重复
 *       （现场 {@code traderserver} 7 条里有 4 个 {@code id=0}），
 *       只有这一对能唯一定位一行 —— 页面表格的 row-key 靠它，
 *       否则会出现「点详情打开的是另一行」。</li>
 * </ol>
 * 各写一遍迟早会在某一份里漏掉其中一点。
 */
@Slf4j
public final class RegistryArrayReader {

    private RegistryArrayReader() {
    }

    /**
     * 一条注册表记录。
     *
     * @param json     该条目的原始 JSON（字段名照现场）
     * @param instance 来源实例（多实例且内容不同，页面要能看出「这条是从哪读的」）
     * @param index    在该实例数组里的下标（id 会重复，靠它才能唯一定位一行）
     */
    public record RegistryEntry(JsonNode json, RedisProperties.Instance instance, int index) {

        public String instanceDisplay() {
            return instance == null ? null : instance.display();
        }
    }

    /**
     * 跨实例把某个注册表键的数组成员摊平。
     *
     * @param values 该键在各实例上的值（来自 {@code findAllAcrossInstances}）
     * @param key    键名 —— 也用于剥掉外面那层同名对象
     * @return 摊平后的条目；一个实例上都没有该键时返回空列表
     */
    public static List<RegistryEntry> flatten(List<RedisJsonValue> values, String key) {
        List<RegistryEntry> out = new ArrayList<>();
        for (RedisJsonValue value : values) {
            JsonNode root = value.json();
            // 值形如 {"traderserver":[...]} / {"strategy-srv":[...]}：外面包了一层同名对象。
            // 兼容「直接就是数组」的形状（万一将来现场改了），但不猜测别的键名。
            JsonNode array = root;
            if (root != null && root.isObject() && root.has(key)) {
                array = root.get(key);
            }
            if (array == null || !array.isArray()) {
                log.warn("注册表键 {} 的值不是数组（实例 {}），已跳过。实际类型：{}",
                        key, value.instanceDisplay(),
                        root == null ? "null" : root.getNodeType());
                continue;
            }
            int index = 0;
            for (JsonNode item : array) {
                out.add(new RegistryEntry(item, value.instance(), index));
                index++;
            }
        }
        return out;
    }

    /** 读 string 字段；缺失 / null / 空串都返回 {@code null}（不制造空字符串） */
    public static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        // 现场有空串，统一按「没有」处理，避免页面出现空白格与空值两种显示
        return s == null || s.isEmpty() ? null : s;
    }

    /** 读 int 字段；缺失返回 {@code null}（**不用 0 顶替** —— 0 是真实存在的值） */
    public static Integer integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asInt();
    }

    /** 读 long 字段；缺失返回 {@code null} */
    public static Long longValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asLong();
    }

    /** 读 boolean 字段；缺失返回 {@code null}（「缺失」与「false」是两件事） */
    public static Boolean bool(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isBoolean()) {
            return null;
        }
        return v.booleanValue();
    }

    /** 供日志用的实例去重计数（同一实例只会读一次该键） */
    public static int copiesOf(List<RegistryEntry> entries) {
        Map<String, Boolean> seen = new java.util.LinkedHashMap<>();
        for (RegistryEntry e : entries) {
            seen.put(e.instanceDisplay(), Boolean.TRUE);
        }
        return seen.size();
    }
}
