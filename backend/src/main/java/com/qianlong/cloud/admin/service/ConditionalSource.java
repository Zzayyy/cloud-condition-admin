package com.qianlong.cloud.admin.service;

/**
 * 条件单的数据源。
 *
 * <p>对应现场的三张表 + 一个视图：
 * <ul>
 *   <li>{@link #RUNNING} → {@code T_Conditional}（正在运行的条件单）</li>
 *   <li>{@link #HISTORY} → {@code T_Conditional_his}（历史条件单）</li>
 *   <li>{@link #ALL} → {@code V_Conditional}（前两张表的并集视图）</li>
 * </ul>
 *
 * <p><b>为什么用枚举而不是直接拼表名：</b>表名要进 SQL 的 {@code ${}} 位置，
 * 而那个位置**不能**用 {@code #{}} 参数化（预编译参数只能出现在值的位置）。
 * 用枚举把可取值**限定死**，前端传什么都只能映射到下面三个常量之一，
 * 从根上排除注入 —— 这比「在 service 里做字符串白名单校验」更难写错。
 *
 * <p>前端 {@code source} 的取值就是这三个字符串（见
 * {@code StrategyList.vue} 的 {@code source = ref<'running'|'history'|'all'>}）。
 */
public enum ConditionalSource {

    RUNNING("running", "T_Conditional", "运行中（主表）"),
    HISTORY("history", "T_Conditional_his", "历史（历史表）"),
    ALL("all", "V_Conditional", "全部（视图 V_Conditional）");
    private final String code;
    private final String table;
    private final String label;

    ConditionalSource(String code, String table, String label) {
        this.code = code;
        this.table = table;
        this.label = label;
    }

    /** 前端传的取值，如 {@code running} */
    public String code() {
        return code;
    }

    /** 真实的表/视图名（**只可能**是枚举里写死的那三个） */
    public String table() {
        return table;
    }

    public String label() {
        return label;
    }

    /**
     * 由前端取值解析；为空时默认 {@link #RUNNING}（与前端默认值一致）。
     *
     * @throws com.qianlong.cloud.admin.common.BizException 取值不认识时
     *         —— **不静默回落**，否则前端传错值只会看到「怎么数据不对」而查不出原因
     */
    public static ConditionalSource of(String value) {
        if (value == null || value.isBlank()) {
            return RUNNING;
        }
        String v = value.trim().toLowerCase();
        for (ConditionalSource s : values()) {
            if (s.code.equals(v)) {
                return s;
            }
        }
        throw com.qianlong.cloud.admin.common.BizException.badRequest(
                "source 取值只能是 running / history / all，实际为：" + value);
    }
}
