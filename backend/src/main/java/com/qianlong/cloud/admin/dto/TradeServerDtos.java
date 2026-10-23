package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 委托服务器模块（{@code /api/trade/servers}、{@code /api/trade/assignments}）的出入参。
 *
 * <p>字段名与前端 {@code src/api/domain.ts} 的 {@code TraderServer} / {@code RedisAccount}
 * 逐一对齐。
 *
 * <p><b>账号分配关系复用 {@link AccountDtos.RedisAccountView}</b>（前端就是
 * {@code RedisAccount}），不另建一套平行结构 —— 分配关系本来就是
 * 「{@code acc-*} 实时对象 + 它挂在哪台委托服务器上」，两处用同一个 DTO
 * 才能保证同一个账号在账号页与委托服务器页显示得一模一样。
 */
public final class TradeServerDtos {

    private TradeServerDtos() {
    }

    /* ============================== 委托服务器节点 ============================== */

    /**
     * 一台委托服务器，对应前端 {@code TraderServer}。
     *
     * <p>前 6 个字段是前端契约；后面几个是**非契约的排查字段**（前端会忽略多余字段）：
     * <ul>
     *   <li>{@code instance} —— 该条从哪个 (host,db) 读到的。现场同一个注册表键在多个
     *       实例上各存一份且**内容不同**，看不出「这条来自哪」就没法排查
     *       「为什么同一台机器出现两次、时间还不一样」。</li>
     *   <li>{@code arrayIndex} —— 在该实例数组里的下标。现场 {@code id} 会重复
     *       （136 db1 的 7 条里有 4 个 {@code id=0}），**只有 (实例, 下标) 能唯一定位一行**；
     *       前端表格的 row-key 需要它，否则会出现「点详情打开的是另一行」。</li>
     *   <li>{@code online} / {@code ageSec} —— 后端按 yml 阈值算好的在线判定与心跳年龄。
     *       前端 {@code isServerOnline} 也会自己算一遍（那是页面本地实时显示，不刷新也能
     *       跳变）；这里给一份后端口径，两边不一致时一眼可见。</li>
     * </ul>
     *
     * <p>⚠️ {@code id} 允许为 {@code null}：现场部分记录缺这个字段，
     * 不要用 0 顶替 —— 0 在现场是**真实存在**的 id 值，顶替会让两者混淆。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TraderServerView(
            Integer id,
            String ip,
            String mac,
            Boolean enable,
            /** ⚠️ 现场字段名带横线，前端契约也是这个名字，不能改名 */
            @JsonProperty("last-active-time") String lastActiveTime,
            Long lastActiveTimeLong,
            /* ---- 以下为非前端契约的排查字段 ---- */
            Boolean online,
            Long ageSec,
            /** 这一行保留的是哪个实例上的副本（= 活跃时间最新的那份） */
            String instance,
            Integer arrayIndex,
            /**
             * 同一个 {@code mac} 在几个实例的注册表里都出现过。
             *
             * <p>列表已按 {@code mac} 归并（否则同一台机器会被列 3~5 遍，
             * 页面"台数"直接是错的），这个字段让「多副本」这件事仍然可见：
             * 为 1 表示只有一份，&gt;1 表示另有 {@code copies-1} 份旧注册。
             */
            Integer copies,
            /**
             * 其余副本的来源实例（不含被保留的那个）。
             * 点开详情能看清「这台机器在哪些库里有历史残留」。
             */
            List<String> otherInstances) {
    }

    /**
     * 节点列表的附加信息（非前端契约字段）。
     *
     * <p>把「这次一共读了几份注册表、有没有实例连不上」如实带出去 —— 否则
     * 「节点列表只有 2 条」会被读成「现场就 2 台」，而真实原因可能是「另外两个库没连上」。
     * 这两种情况的处置完全不同，不能在接口层抹平。
     */
    public record TradeServerListMeta(
            /** 读到注册表的份数（每存在该键的实例算一份） */
            int registryCopies,
            /** 摊平后的条目总数（**未去重**，即注册表里的原始记录数） */
            int entryTotal,
            /**
             * 按 {@code mac} 归并后的**台数**。
             *
             * <p>列表与卡片都用这个数，不用 {@code entryTotal} ——
             * 后者会把同一台机器在多个库里的历史残留各算一次，页面"台数"就是错的。
             */
            int uniqueTotal,
            /** 其中在线数（按 offlineAfterSec 判定，与页面同一口径；按归并后的台数统计） */
            int onlineCount,
            /** 心跳阈值（秒）——回传出去让页面自检与前端常量是否一致 */
            int offlineAfterSec,
            /** 配置里声明的实例数 */
            int configuredInstances,
            /** 可用的实例名 */
            List<String> redisInstances,
            /** 不可用的实例（实例 id -> 原因），非空表示列表可能不完整 */
            List<String> redisUnavailable,
            String registryKey,
            /** 心跳频道实际名字（已拼上 suffix，现场是 _1 那一套） */
            String onlineChannel,
            String beatChannel,
            String offlineChannel) {
    }

    /** 节点列表返回（行 + 元信息） */
    public record TradeServerPage(
            List<TraderServerView> list,
            long total,
            long page,
            long pageSize,
            TradeServerListMeta meta) {
    }

    /* ================================ 查询参数 ================================ */

    /** 节点列表查询参数（与前端 {@code serverParams()} 一致） */
    public static class TraderServerQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        /**
         * true=只看在线，false=只看离线/停用；未传=全部。
         *
         * <p>用 {@link Boolean} 接收：前端传的是**布尔**（{@code online: true}）。
         * 若声明成 String，Spring 也能绑上，但「传了 false」与「没传」会长得一样，
         * 那样「只看离线」会静默变成「全部」。
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

    /** 账号分配关系查询参数（与前端 {@code accParams()} 一致） */
    public static class AssignmentQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        /** 只看在线 / 只看离线；未传=不过滤 */
        private Boolean online;
        /** 只看分配到某台委托服务器（按 id 匹配，与前端 mock 口径一致） */
        private Integer serverId;
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

        public Integer getServerId() {
            return serverId;
        }

        public void setServerId(Integer serverId) {
            this.serverId = serverId;
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
