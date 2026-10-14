package com.qianlong.cloud.admin.dto;

import java.util.List;
import java.util.Map;

/**
 * Redis 管理模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 的
 * {@code RedisInstance} / {@code RedisInstanceInfo} / {@code RedisKeyInfo} /
 * {@code RedisKeyDetail} 逐一对齐。
 */
public final class RedisAdminDtos {

    private RedisAdminDtos() {
    }

    /* ============================== 实例 ============================== */

    /**
     * 实例，对应前端 {@code RedisInstance}。
     *
     * <p>⚠️ 这里的语义是「**一台 Redis 主机**」，不是「一个 (host,db) 组合」：
     * 管理页要浏览整台实例的所有 db，所以 {@code id} 用主机地址，
     * {@code db} 给一个建议的起始 db（有键的第一个）。
     * 业务侧的 (host,db) 组合清单在 {@code app.redis.instances}，两者用途不同。
     */
    public record RedisInstanceView(String id, String name, String host, int port, int db) {
    }

    /**
     * 实例概览，对应前端 {@code RedisInstanceInfo}。
     *
     * <p>{@code dbKeys} 是**键数**（DBSIZE 口径），不是条目数 ——
     * 现场 137 db0 只有 117 个键，但其中一个 stream 有 1400 万条条目。
     * 两者混看会得出完全错误的容量判断，所以接口把它们分成不同字段。
     */
    public record RedisInstanceInfoView(
            String host,
            int port,
            String version,
            String mode,
            String os,
            Long uptimeInSeconds,
            Long connectedClients,
            Long usedMemory,
            String usedMemoryHuman,
            String usedMemoryPeakHuman,
            /** 0 表示无上限（现场两个实例都是 0） */
            Long maxmemory,
            String maxmemoryPolicy,
            Long totalCommandsProcessed,
            Long keyspaceHits,
            Long keyspaceMisses,
            List<DbKeyCount> dbKeys) {

        /** 某个 db 的键数 */
        public record DbKeyCount(int db, long keys) {
        }
    }

    /* ============================== 键 ============================== */

    /** 键摘要，对应前端 {@code RedisKeyInfo} */
    public record RedisKeyInfoView(
            String key,
            String type,
            /**
             * TTL 毫秒：{@code -1} 永不过期，{@code -2} 键不存在（PTTL 语义）。
             * 用毫秒是因为现场有 4 秒 / 14 秒这种短 TTL 的锁键，秒级精度会丢信息。
             */
            long pttl,
            /** 长度/条目数；非容器类型（string / ReJSON）为 null */
            Long length,
            /** 业务归属（按前缀推断，仅作归类参考） */
            String domain) {
    }

    /**
     * 扫描结果（对应前端 {@code PageResult<RedisKeyInfo>}，另加元信息）。
     *
     * <p>前端契约只认 {@code list} / {@code total} / {@code page} / {@code pageSize}，
     * 其余字段前端会忽略，但排查时很有用。
     */
    public record ScanResult(
            List<RedisKeyInfoView> list,
            long total,
            long page,
            long pageSize,
            /** 来源实例（如 192.168.1.136/db0） */
            String instance,
            int db,
            /** 该 db 的**真实键数**（DBSIZE），与 total（过滤后条数）不是一回事 */
            long dbSize,
            /**
             * 是否因为服务端上限而**只扫了一部分**。
             *
             * <p>⚠️ 必须如实告知：现场 137 db0 的 stream 有 1400 万条条目，
             * 服务端不可能扫完全部键。若静默截断，用户会以为
             * 「这个 db 就这么多键」—— 那是会误导容量判断的。
             */
            boolean truncated,
            /** 服务端单次扫描上限（被截断时告诉用户上限是多少） */
            int scanLimit) {
    }

    /**
     * 键详情，对应前端 {@code RedisKeyDetail}。
     *
     * <p>按类型返回不同结构，页面据此选渲染方式。
     */
    public record RedisKeyDetailView(
            String key,
            String type,
            long pttl,
            Long length,
            /** string 类型的原始值 */
            String value,
            /** ReJSON-RL / 结构化内容的原文（页面会格式化展示） */
            String json,
            /** stream 的最近条目（最多若干条） */
            List<StreamEntry> entries,
            /** 集合类类型（list/set/zset/hash）的成员，最多若干条 */
            List<String> members,
            /** 内存占用（MEMORY USAGE，字节）；不可用时为 null */
            Long memoryUsage,
            /** 编码方式（OBJECT ENCODING）；不可用时为 null */
            String encoding,
            /** 来源实例 */
            String instance,
            int db) {

        /** stream 的一条条目 */
        public record StreamEntry(String id, Map<String, String> fields) {
        }
    }

    /* ============================== 发布订阅 ============================== */

    /**
     * Pub/Sub 频道，对应前端 {@code RedisPubSubChannel}。
     *
     * <p>⚠️ 现场实测：136 有 14 个频道（每个 1 个订阅者）、137 **0 个**。
     * 这个差异是否正常已记在 README 待确认项里 —— 接口如实返回，不解释成「正常」。
     */
    public record RedisPubSubChannelView(
            String name,
            long subscribers,
            /** 频道分组：trade / strategy / datahub / directed / other */
            String group,
            String instance) {
    }

    /* ============================== 删除 ============================== */

    /** 删除键的入参 */
    public record DeleteKeyRequest(String instanceId, Integer db, String key) {
    }
}
