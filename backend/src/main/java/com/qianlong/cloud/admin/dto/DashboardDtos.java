package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 总览页（{@code /api/dashboard/overview}）的出入参。
 *
 * <h2>本页的设计原则：能算的算准，算不出的说清楚，不粉饰</h2>
 * 需求方明确要求「按如实做」—— 现场当前**没有在发数据**，所以这一页
 * 第一眼不会好看（策略服务器全离线、大部分流已停止、成交率为 0）。
 * 那是**真实状况**，页面照实呈现，而不是把数字修饰成健康的。
 *
 * <h2>⚠️ 三处「不能简单相减」的口径</h2>
 * <ol>
 *   <li><b>节点在线数</b>：三类服务器的「离线」含义完全不同 ——
 *       委托服务器是真有活的、策略服务器是**服务未开放**（离线是必然）、
 *       数据中台是**观测不到**（不等于离线）。合并成一个「离线 N 个」
 *       会让人以为「大面积宕机」。所以三类分开，且中台保留 {@code unknown}。</li>
 *   <li><b>成交率</b>：现场委托 OrderNo（328~335）在成交表里**找不到对应**
 *       （成交表只有 OrderNo=1234567 那条测试数据），所以成交率是 0%。
 *       那不是「策略全废」，而是「成交没落库」—— 页面必须能区分这两件事。</li>
 *   <li><b>消息总线积压</b>：1416 万条的积压看着骇人，但实测**67/149 条流
 *       已超过 1 小时没有新条目**、入口流最后一条在 24 小时前 ——
 *       这是**历史堆积**而非当前流量。只报积压数会把「一潭死水」
 *       说成「流量很大」，结论完全相反。</li>
 * </ol>
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /* ================================ 汇总 ================================ */

    /**
     * 服务器节点健康度。
     *
     * <p>⚠️ 三类必须分开，且中台有 {@code unknown} —— 原因见类注释第 1 条。
     */
    public record NodeSummary(
            int tradeTotal, int tradeOnline,
            int strategyTotal, int strategyOnline,
            int datahubTotal, int datahubOnline,
            /** 数据中台里**观测不到**的实例数（订阅关闭/连接失败/还没收到心跳） */
            int datahubUnknown,
            /** 策略服务器的注册表是否已陈旧（服务未开放时为 true） */
            boolean strategyRegistryStale,
            /** 数据中台心跳订阅是否开启（关掉时在线数不可信） */
            boolean datahubHeartbeatEnabled) {
    }

    /** 条件单汇总（口径：V_Conditional 并集视图） */
    public record ConditionalSummary(long total, long running, long triggered) {
    }

    /** 委托单汇总 */
    public record OrderSummary(long total, long filled) {
    }

    /** 成交回报汇总 */
    public record TradeSummary(long total, double volume) {
    }

    /** 云单账号汇总（MySQL T_User 与 Redis acc-* 合并口径） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountSummary(
            long total,
            long online,
            /** 只在 Redis 有实时对象、MySQL 里没有的账号数（两边不一致的信号） */
            long redisOnly,
            /** MySQL 有、Redis 无实时对象的账号数 */
            long mysqlOnly) {
    }

    /**
     * 消息总线汇总。
     *
     * <p>⚠️ {@code staleStreams} 与 {@code ingressBacklogAgeSec} 是本记录里
     * **最该被看见**的两个字段：它们回答「这些积压是活的还是死的」。
     */
    public record StreamSummary(
            long count,
            /** 入口流积压（各实例 DataHub_req_stream 的 XLEN 合计） */
            long ingressBacklog,
            long consumerLag,
            long pending,
            /** **没有消费组**的流数 —— 写入后无人投递，最危险的一类 */
            long groupLess,
            /** 已超过 1 小时没有新条目的流数（数据流可能已停止） */
            long staleStreams,
            /** 入口流里**最新**的那条条目距今多少秒；无数据时为 null */
            Long ingressBacklogAgeSec,
            /** 全部流条目合计 */
            long entryTotal) {
    }

    /**
     * 总览汇总。
     *
     * <p>{@code generatedAt} 是本次快照的时刻 —— 页面显示它，
     * 让人知道这些数字是哪一刻的（Redis 与 MySQL 两边不是同一事务里读的）。
     */
    public record DashboardSummary(
            NodeSummary nodes,
            ConditionalSummary conditional,
            OrderSummary order,
            TradeSummary trade,
            AccountSummary account,
            StreamSummary stream,
            /** 本次是否需要提醒「现场没有在发数据」 */
            boolean idle,
            /** 沉默的原因说明（null 表示一切正常） */
            String idleReason,
            String generatedAt) {
    }

    /* ============================ 数据来源与规模 ============================ */

    /** 「数据来源与规模」表的一行 */
    public record SourceRow(String name, String kind, String kindLabel,
                            String scale, String hint) {
    }

    /** 近 N 天趋势的一行 */
    public record DailyRow(String date, long conditional, long order, long trade) {
    }

    /** 总览返回 */
    public record DashboardOverview(
            DashboardSummary summary,
            List<SourceRow> sources,
            List<DailyRow> daily,
            String generatedAt) {
    }
}
