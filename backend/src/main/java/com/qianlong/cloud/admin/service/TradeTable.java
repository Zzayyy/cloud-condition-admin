package com.qianlong.cloud.admin.service;

/**
 * 委托单/成交的数据源（主表 vs 历史表）。
 *
 * <p>对应现场的四张表：
 * <ul>
 *   <li>{@link #ORDER} → {@code T_Order}（委托主表）</li>
 *   <li>{@link #ORDER_HIS} → {@code T_Order_his}（委托历史表）</li>
 *   <li>{@link #TRADE} → {@code T_Trade}（成交主表）</li>
 *   <li>{@link #TRADE_HIS} → {@code T_Trade_his}（成交历史表）</li>
 * </ul>
 *
 * <p><b>为什么用枚举而不是直接拼表名：</b>表名要进 SQL 的 {@code ${}} 位置，
 * 而那个位置**不能**用 {@code #{}} 参数化（预编译参数只能出现在值的位置）。
 * 枚举把可取值**限定死**，前端传什么都只能映射到这四个常量之一，
 * 从根上排除注入。
 *
 * <p><b>前端传的取值就是真实表名</b>（见 {@code TradeOrder.vue} 的
 * {@code table: tab.value === 'active' ? 'T_Order' : 'T_Order_his'}）——
 * 前端的写法让表名直接来自用户可见的 tab，所以后端必须严格白名单，
 * 不能信任这个字符串。
 */
public enum TradeTable {

    ORDER("T_Order", "委托主表"),
    ORDER_HIS("T_Order_his", "委托历史表"),
    TRADE("T_Trade", "成交主表"),
    TRADE_HIS("T_Trade_his", "成交历史表");

    private final String table;
    private final String label;

    TradeTable(String table, String label) {
        this.table = table;
        this.label = label;
    }

    /** 真实表名（**只可能**是枚举里写死的四个之一） */
    public String table() {
        return table;
    }

    public String label() {
        return label;
    }

    /** 按前端传的表名解析；为空时默认委托主表 */
    public static TradeTable ofOrder(String value) {
        if (value == null || value.isBlank() || ORDER.table.equals(value.trim())) {
            return ORDER;
        }
        if (ORDER_HIS.table.equals(value.trim())) {
            return ORDER_HIS;
        }
        throw com.qianlong.cloud.admin.common.BizException.badRequest(
                "委托单的 table 取值只能是 T_Order / T_Order_his，实际为：" + value);
    }

    /** 成交单的成交表解析 */
    public static TradeTable ofTrade(String value) {
        if (value == null || value.isBlank() || TRADE.table.equals(value.trim())) {
            return TRADE;
        }
        if (TRADE_HIS.table.equals(value.trim())) {
            return TRADE_HIS;
        }
        throw com.qianlong.cloud.admin.common.BizException.badRequest(
                "成交单的 table 取值只能是 T_Trade / T_Trade_his，实际为：" + value);
    }
}
