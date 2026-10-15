package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 数据中台模块（{@code /api/datahub/*}）的出入参。
 *
 * <p><b>与委托服务器模块最大的不同：本模块的「节点」不是从某个注册表键读来的。</b>
 * 中台在 Redis 里没有节点清单（现场翻遍 136/137 只有
 * {@code DataHub_req_stream} / {@code DataHub_reply_stream} / {@code unique_id}），
 * 所以一行 = <b>一台 Redis 实例</b>，其「在线」= 该实例上是否收到过中台心跳。
 *
 * <p>对应地，前端 {@code DatahubNode} 里那几个<b>没有数据源的字段</b>
 * （{@code mac}、{@code lastActiveTime}）在本模块<b>不提供</b>，
 * 页面也已把列删掉 —— 留一列永远空着的「MAC」只会让人以为「没读到」。
 */
public final class DatahubDtos {

    private DatahubDtos() {
    }

    /* ================================ 节点（实例） ================================ */

    /**
     * 一行 = 一台 Redis 实例。
     *
     * <p>{@code online} 是三态中的两态 + 未知：
     * <ul>
     *   <li>{@code true} —— 该实例上近期收到过中台心跳；</li>
     *   <li>{@code false} —— 曾经收到过，但现在超过了阈值；</li>
     *   <li>{@code null} —— <b>观测不到</b>（订阅关闭/连接失败/还没收到过第一条）。</li>
     * </ul>
     * 刻意用可空 Boolean 而不是 boolean：把「未知」压成 {@code false} 会把
     * 「我们没在看」说成「它离线了」，对一个专门看「活着吗」的页面这是最糟的错。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DatahubNodeView(
            /** 实例标识。本模块就取主机地址（与 Redis 管理页同一口径）。 */
            String id,
            String ip,
            String port,
            /** 该实例上有没有中台在通信；**null = 未知**（不是离线） */
            Boolean online,
            /** 状态原文：ONLINE / OFFLINE / UNKNOWN —— 比布尔更能说明「未知」的原因 */
            String state,
            /** 距上次心跳秒数；从未收到为 null */
            Long lastBeatAgeSec,
            /** 累计收到的心跳条数；0 = 一条都没收到 */
            long beatCount,
            /** 本实例的订阅是否正常建立 */
            boolean subscribed,
            /** 订阅失败原因（没有则 null） */
            String subscribeError,
            /** 该实例各 db 的键数（DBSIZE 口径，键数 ≠ 条目数） */
            List<DbStat> dbs,
            /** 键数合计（各 db 相加） */
            long dbKeysTotal,
            /** stream 条目合计（跨该实例全部 db） */
            long entryTotal,
            /** stream 数合计 */
            int streamTotal,
            /** 频道数（Pub/Sub 是实例级，与 db 无关） */
            int channels) {
    }

    /**
     * 一个 db 的统计。
     *
     * <p>⚠️ {@code uniqueId} 是**各 db 独立**的（它是 string 键，不是全局计数器）：
     * 现场实测 136 db0=217 / db1=56、137 db1=4，而 137 db0 与 db2 <b>没有这个键</b>
     * （返回 null，不是 0 —— 0 是一个真实水位，不能用来表示「没有」）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DbStat(
            int db,
            long keys,
            /** 该 db 的 unique_id 水位；该 db 没有这个键时为 null */
            Long uniqueId,
            /** 该 db 的 stream 条目合计 */
            long entryTotal,
            int streamTotal) {
    }

    /**
     * 节点列表的附加信息。
     *
     * <p>{@code heartbeatsObserved} / {@code heartbeatChannels} 让页面能说清
     * 「我们在听什么频道、听了多少条」—— 否则「在线 0 个」到底是
     * 「真没有中台」还是「频道名听错了」无法区分。这是本模块最容易踩的坑。
     */
    public record DatahubNodeListMeta(
            int total,
            /** 在线实例数（只看确定的 true） */
            int onlineCount,
            /** 离线实例数（确定的 false） */
            int offlineCount,
            /** **未知**实例数 —— 必须单独计数，不能并进「离线」 */
            int unknownCount,
            /** 是否开启了心跳订阅 */
            boolean heartbeatEnabled,
            /** 实际订阅的频道名（确认听的是对的那一套） */
            List<String> heartbeatChannels,
            /** 心跳阈值（秒） */
            int offlineAfterSec,
            /** 观测到的实例数 */
            int observedInstances,
            List<String> redisInstances,
            List<String> redisUnavailable,
            /**
             * ⚠️ 关于「中台没有一个节点清单」的如实说明，直接给页面展示。
             * 这句不是装饰 —— 没有它，用户会以为「节点数 = 中台数」。
             */
            String note) {
    }

    /** 节点列表返回（行 + 元信息） */
    public record DatahubNodePage(
            List<DatahubNodeView> list,
            long total,
            long page,
            long pageSize,
            DatahubNodeListMeta meta) {
    }

    /* ================================== 频道 ================================== */

    /**
     * 一个 Pub/Sub 频道（含订阅者数）。字段名与前端 {@code RedisPubSubChannel} 一致。
     *
     * <p>⚠️ {@code subscribers} 已经**扣掉本后台自己的那份订阅**：
     * 我们为了让心跳判定可用而常驻订阅了 {@code datahub_beat_1}，
     * 若不扣除，页面上的订阅者数会永远多 1（现场 137 甚至会凭空多出一个频道）。
     */
    public record DatahubChannelView(
            String name,
            long subscribers,
            /** trade / strategy / datahub / directed / other */
            String group,
            String instance,
            /**
             * 该频道是否**只有本后台在订阅**（扣除前订阅者数恰好等于我们的份数）。
             * 保留这个标记而不是静默减掉 —— 「这个频道其实没人在听」
             * 与「确实有人在听」是完全不同的结论。
             */
            boolean selfSubscribed) {
    }

    /* ================================== 流 ================================== */

    /**
     * 一条 stream。
     *
     * <p>字段名与前端 {@code RedisStreamInfo} 对齐：{@code key} / {@code length} /
     * {@code groups} / {@code instance} / {@code db} / {@code role}。
     *
     * <p>⚠️ {@code length} 是 {@code XLEN}（条目数），不是键数。
     * 现场 137 db0 的 DBSIZE 只有 117，但单个 {@code DataHub_req_stream}
     * 就有 1400 万条目 —— 两者混着看会得出完全错误的容量判断。
     */
    public record DatahubStreamView(
            String key,
            long length,
            List<StreamGroupView> groups,
            String instance,
            int db,
            /** ingress / reply / downstream / ack / other */
            String role) {
    }

    /** 消费组（含积压）。{@code lag} 允许为 null —— 老版本 Redis 不返回该字段。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StreamGroupView(
            String name,
            long consumers,
            long pending,
            /** ⚠️ 字段名带横线，与前端契约一致 */
            @com.fasterxml.jackson.annotation.JsonProperty("last-delivered-id") String lastDeliveredId,
            Long lag) {
    }

    /** 流的查询参数（与前端 {@code listStreams(params)} 一致） */
    public static class StreamQuery {
        private String instance;
        private Integer db;
        private String role;
        private String keyword;
        private String sortBy;
        private String sortOrder;

        public String getInstance() {
            return instance;
        }

        public void setInstance(String instance) {
            this.instance = instance;
        }

        public Integer getDb() {
            return db;
        }

        public void setDb(Integer db) {
            this.db = db;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public String getSortBy() {
            return sortBy;
        }

        public void setSortBy(String sortBy) {
            this.sortBy = sortBy;
        }

        public String getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(String sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    /* ================================ 流条目 ================================ */

    /** 一条 stream 条目。字段名与前端 {@code RedisStreamEntry} 一致。 */
    public record DatahubStreamEntryView(String id, java.util.Map<String, String> fields) {
    }

    /**
     * 流条目分页结果。
     *
     * <p>{@code truncatedWindow} 是本接口最重要的一个字段：
     * 现场入口流有 1400 万条，**不可能**全量返回。实际做法是在**尾部窗口**内取，
     * 所以「查不到某个 request_id」并不等于「它不存在」——
     * 页面必须把这句说明显示出来，否则排查的人会得出错误结论。
     */
    public record DatahubEntryPage(
            List<DatahubStreamEntryView> list,
            long total,
            long page,
            long pageSize,
            /** 本次是在最近多少条里查的（尾部窗口大小） */
            long windowSize,
            /** 该流真实总长度（XLEN）；远大于 windowSize 时说明窗口外还有历史 */
            long streamLength,
            /** true = 只查了尾部窗口，窗口外可能还有数据 */
            boolean truncatedWindow,
            String instance,
            int db,
            String key) {
    }

    /* ============================== 请求追踪 ============================== */

    /** 追踪结果：按 request_id 串联到的各条报文 */
    public record DatahubTraceView(
            String requestId,
            List<TraceHit> hits,
            /** 本次在哪些 (实例,db,流) 的尾部窗口里找过 */
            List<String> searched,
            /** 每个流各查了多少条 */
            long windowSize,
            /** true = 只在尾部窗口内查找，窗口外可能还有 */
            boolean truncatedWindow,
            /** 是否找到 */
            boolean found) {
    }

    /** 一条命中的报文（含它在哪个流的哪条） */
    public record TraceHit(
            String instance,
            int db,
            String stream,
            String entryId,
            java.util.Map<String, String> fields) {
    }
}
