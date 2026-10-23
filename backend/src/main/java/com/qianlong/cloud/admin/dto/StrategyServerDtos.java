package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 策略服务器模块（{@code /api/strategy/servers}）的出入参。
 *
 * <p>字段名与前端 {@code src/api/domain.ts} 的 {@code StrategyServer} 逐一对齐。
 * 结构照 Redis 键 {@code strategy-srv} 的现场实况：
 * <pre>
 *   { "strategy-srv": [ { id, ip, mac, usecount, enable,
 *                         "login-time", "last-active-time", lastActiveTimeLong } ] }
 * </pre>
 *
 * <p>⚠️ 与委托服务器同理：{@code id} 会重复、{@code mac} 是本机网卡地址拼了
 * 实例名后缀，所以行的唯一标识是 {@code (来源实例, 数组下标)}，
 * 接口一并返回 {@code instance} 与 {@code arrayIndex}。
 */
public final class StrategyServerDtos {

    private StrategyServerDtos() {
    }

    /**
     * 一台策略服务器。
     *
     * <p>前 8 个字段是前端契约；后面几个是非契约的排查字段（前端忽略多余字段）。
     *
     * <p>⚠️ {@code id} 允许为 {@code null}：现场部分记录缺字段，
     * 不要用 0 顶替 —— 0 在现场是**真实存在**的 id 值，顶替会让两者混淆。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StrategyServerView(
            Integer id,
            String ip,
            String mac,
            /** 该节点承载的条件单用户数。⚠️ 现场全部为 0（见类注释与 meta.note） */
            Integer usecount,
            /** ⚠️ 现场字段名带横线，前端契约也是这个名字，不能改名 */
            @JsonProperty("login-time") String loginTime,
            @JsonProperty("last-active-time") String lastActiveTime,
            Long lastActiveTimeLong,
            Boolean enable,
            /* ---- 以下为非前端契约的排查字段 ---- */
            /** 后端按心跳阈值算好的在线判定（页面本地那份会随时间跳变） */
            Boolean online,
            /** 距上次心跳的秒数 */
            Long ageSec,
            /** 来源实例，如 192.168.1.136/db0（现场多实例且内容不同） */
            String instance,
            /** 在该实例数组里的下标（id 会重复，只有它+实例能唯一定位一行） */
            Integer arrayIndex,
            /**
             * 同一个 {@code mac} 在几个实例的注册表里都出现过。
             *
             * <p>列表已按 {@code mac} 归并 —— 否则同一台机器会被列多遍、
             * 页面"台数"直接是错的（现场 2 条只对应 1 台）。
             * 为 1 表示只有一份，&gt;1 表示另有 {@code copies-1} 份旧注册。
             */
            Integer copies,
            /** 其余副本的来源实例（不含被保留的那个） */
            List<String> otherInstances) {
    }

    /**
     * 列表附加信息。
     *
     * <h2>为什么这些汇总必须由后端给</h2>
     * 前端页面原先是**按当前页** `filter` 算「在线节点」的
     * （代码里自己写了注释「真实汇总应由后端单独提供接口」）。
     * 那样得出的数字是「第 1 页有几台在线」，而页面写的是「在线节点数」——
     * 翻页时数字会莫名变化。
     *
     * <h2>为什么要有 registryStale</h2>
     * 现场**策略服务器未开放**：注册表不刷新、心跳也收不到，
     * 所以「在线 0 台」是必然结果。若不说明，看页面的人会去查节点，
     * 而真正该知道的是「这个注册表本身就没在更新」。
     */
    public record StrategyServerListMeta(
            /** 全量在线数（按 offlineAfterSec 判定，与页面同一口径） */
            int onlineCount,
            int offlineCount,
            /**
             * 全量「节点总数」= **按 {@code mac} 归并后的台数**（现场 2 条 = 1 台）。
             *
             * <p>卡片与列表都用这个数。以前这里是"跨实例摊平后的总条数"，
             * 于是同一台机器在多个库里各注册一份就会被数成好几台。
             */
            int total,
            /**
             * 摊平后的**原始条数**（未去重）。
             *
             * <p>`entryTotal - total` = 重复注册的份数，排查「为什么这台机器在多处出现」时用。
             */
            int entryTotal,
            /** 全量 usecount 合计（不是当前页合计） */
            long usecountTotal,
            /** 全量 usecount 最大值 —— 进度条的分母，用当前页最大会导致翻页时跳动 */
            int usecountMax,
            /** 心跳阈值（秒）—— 回传出去让页面自检与前端常量是否一致 */
            int offlineAfterSec,
            /** 读到注册表的份数（每存在该键的实例算一份） */
            int registryCopies,
            String registryKey,
            List<String> redisInstances,
            List<String> redisUnavailable,
            /* ---- 注册表陈旧度：区分「节点都离线」与「注册表没在更新」 ---- */
            /** 注册表里最新那条心跳距今多少秒；一条都没有时为 null */
            Long newestAgeSec,
            /** true = 注册表已超过 staleRegistryAfterSec 没变化（现场策略服务器未开放就是这种） */
            boolean registryStale,
            /** 陈旧判定的阈值（秒） */
            int staleRegistryAfterSec,
            /**
             * 现场现状的如实说明，直接给页面展示。
             * 重点是「心跳收不到是预期现象」与「usecount 全部为 0 不代表没承载」。
             */
            String note) {
    }

    /** 列表返回（行 + 元信息） */
    public record StrategyServerPage(
            List<StrategyServerView> list,
            long total,
            long page,
            long pageSize,
            StrategyServerListMeta meta) {
    }

    /** 查询参数（与前端 {@code currentParams()} 一致） */
    public static class StrategyServerQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        /**
         * true=只看在线，false=只看离线/停用；未传=全部。
         *
         * <p>用 {@link Boolean} 接收（前端传的是布尔）。若声明成 String，
         * 「传了 false」与「没传」会长得一样，「只看离线」会静默变成「全部」。
         */
        private Boolean online;
        private String sortBy;
        private String sortOrder;

        public int pageOrDefault() {
            return page == null || page < 1 ? 1 : page;
        }

        public int pageSizeOrDefault() {
            if (pageSize == null || pageSize < 1) {
                return 20;
            }
            return Math.min(pageSize, 200);
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public Integer getPageSize() {
            return pageSize;
        }

        public void setPageSize(Integer pageSize) {
            this.pageSize = pageSize;
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Boolean getOnline() {
            return online;
        }

        public void setOnline(Boolean online) {
            this.online = online;
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
}
