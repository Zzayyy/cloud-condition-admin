package com.qianlong.cloud.admin.service;

/**
 * 条件单的数据源。
 *
 * <h2>⚠️ 2026-10-21：语义从「查哪张表」改成「筛哪类状态」</h2>
 * 原来三个取值对应三张表（{@code running}=主表 / {@code history}=历史表 /
 * {@code all}=并集视图）。但主数据源换成 Redis {@code strategy-*} 之后，
 * <b>Redis 侧没有"主表/历史表"之分</b>，只有状态（{@code Mode} / {@code Flag}）——
 * 所以现在按状态筛：
 *
 * <pre>
 *   running → Mode = 1                      （正在运行）
 *   history → Mode ≠ 1 或 Flag = 2          （停止 / 失效 / 结束 / 已删除）
 *   all     → 不筛
 * </pre>
 *
 * <p>页面上的三个 tab 文案也要跟着改（原来写的是「运行中（T_Conditional）」，
 * 那是表名，现在不再是查表了）。
 *
 * <p><b>为什么还用枚举而不是直接拼表名：</b>表名要进 SQL 的 {@code ${}} 位置，
 * 而那个位置**不能**用 {@code #{}} 参数化（预编译参数只能出现在值的位置）。
 * 用枚举把可取值**限定死**，前端传什么都只能映射到下面三个常量之一，
 * 从根上排除注入 —— 这比「在 service 里做字符串白名单校验」更难写错。
 */
public enum ConditionalSource {

    RUNNING("running", "T_Conditional", "运行中", "Mode=1 且未删除（Flag≠2）"),
    HISTORY("history", "T_Conditional_his", "已停止 / 已失效 / 已删除", "Mode≠1 或已删除（Flag=2）"),
    ALL("all", "V_Conditional", "全部", "全部状态");

    private final String code;
    private final String table;
    private final String label;
    private final String scope;

    ConditionalSource(String code, String table, String label, String scope) {
        this.code = code;
        this.table = table;
        this.label = label;
        this.scope = scope;
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

    /** 筛选口径的说明（页面 tooltip 用，让人知道这个 tab 到底筛了什么） */
    public String scopeText() {
        return scope;
    }

    /**
     * 判断一条条件单是否属于这个来源。
     *
     * <p><b>必须是不重不漏的二分</b>（否则「全部 = 运行中 + 已结束」这条恒等式
     * 会对不上，页面上的卡片与列表就会互相打架）。
     * 现场踩到过：最初写成「running = Mode=1」「history = Mode≠1 <b>或</b> Flag=2」，
     * 于是 Mode=1 且 Flag=2（已删除但状态还停在运行）的单<b>同时</b>落进两个 tab，
     * running 48 + history 21 = 69，比 all 的 56 多出 13 条。
     *
     * @param mode 云单状态：0 停止 / 1 运行 / 2 失效 / 3 结束
     * @param flag 有效标志：0 有效 / 1 暂停 / 2 删除
     */
    public boolean matches(Integer mode, Integer flag) {
        boolean deleted = flag != null && flag == 2;
        return switch (this) {
            case RUNNING -> mode != null && mode == 1 && !deleted;
            case HISTORY -> mode == null || mode != 1 || deleted;
            case ALL -> true;
        };
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
