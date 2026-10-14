package com.qianlong.cloud.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.qianlong.cloud.admin.service.RedisJsonGateway.RedisJsonValue;

import lombok.RequiredArgsConstructor;

/**
 * Redis {@code acc-*} 键的只读访问（账户与用户模块）。
 *
 * <p>连接管理、SCAN、ReJSON 读取都委托给 {@link RedisJsonGateway} ——
 * 这里只负责「acc- 前缀」这一件事。
 * 条件单页要读 {@code strategy-*}，若各自实现一遍连接与扫描逻辑，
 * 迟早会在某一份里漏掉「SCAN 必须循环」这类细节。
 */
@Repository
@RequiredArgsConstructor
public class RedisAccountRepository {

    /** acc-* 键前缀（照现场命名规律） */
    private static final String ACC_PREFIX = "acc-";

    private final RedisJsonGateway gateway;

    /** 当前不可用的实例（实例 id -> 原因） */
    public Map<String, String> unavailableInstances() {
        return gateway.unavailableInstances();
    }

    /** 可用实例名 */
    public List<String> availableInstanceNames() {
        return gateway.availableInstanceNames();
    }

    /**
     * 扫描全部实例，取出 acc-* 键。
     *
     * @return key（即 acc-xxx_类型_渠道）-> 该键的解析结果 + 来源实例
     */
    public Map<String, RedisAccountValue> findAll() {
        Map<String, RedisAccountValue> out = new LinkedHashMap<>();
        gateway.scanJson(ACC_PREFIX, null).forEach((key, value) ->
                out.put(key, new RedisAccountValue(
                        value.json(), value.instance(), value.key(), value.rawJson())));
        return out;
    }

    /** 取单个 acc 键（详情接口用）。找不到返回 null。 */
    public RedisAccountValue findOne(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return null;
        }
        String key = accountKey.startsWith(ACC_PREFIX) ? accountKey : ACC_PREFIX + accountKey;
        RedisJsonValue value = gateway.findOne(key);
        return value == null ? null
                : new RedisAccountValue(value.json(), value.instance(), value.key(), value.rawJson());
    }

    /**
     * 一个 acc-* 键的值。
     *
     * @param json     解析后的 JSON（字段名照现场：useraccount/accounttype/maintype/onlineflag/...）
     * @param instance 来源实例（页面要显示「这条实时态是从哪读到的」）
     * @param key      完整键名
     * @param rawJson  原始 JSON 文本（页面「原始内容」tab 要原样展示，不做二次加工）
     */
    public record RedisAccountValue(JsonNode json, com.qianlong.cloud.admin.config.RedisProperties.Instance instance,
                                    String key, String rawJson) {

        public String instanceDisplay() {
            return instance == null ? null : instance.display();
        }
    }
}
