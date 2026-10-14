package com.qianlong.cloud.admin.dto;

import java.util.List;

/**
 * 策略日志模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 的
 * {@code StrategyLog} 逐一对齐（**首字母大写**，前端照搬了 MySQL 列名）。
 *
 * <p>注意 {@code OccurTIme} 里那个大写的 {@code I} 是现场列名就这么写的，
 * 不是笔误，不能「顺手改成 OccurTime」—— 改了就和前端契约不符。
 */
public final class StrategyLogDtos {

    private StrategyLogDtos() {
    }

    /**
     * 策略日志，对应前端 {@code StrategyLog}。
     *
     * <p>⚠️ {@code Status} / {@code FromIP} / {@code FromMac} 现场**整列为 NULL**，
     * 会如实返回 {@code null}（前端渲染成 {@code -}），
     * 不编造占位值 —— 编了就看不出「这一列根本没有数据」。
     */
    public record StrategyLogView(
            Integer id,
            Long Strategy,
            String Status,
            String Info,
            /** {@code date} 类型，格式 yyyy-MM-dd，无时分秒 */
            String OccurTIme,
            String FromIP,
            String FromMac,
            String FromAccount) {
    }

    /** 按天日志量（趋势图用） */
    public record DailyLogRow(String date, long count) {
    }

    /** 按条件单聚合（「按策略单查看生命周期」） */
    public record StrategyStatRow(Long strategy, long count, String firstDay, String lastDay) {
    }

    /**
     * 列表附加信息（非前端契约字段，前端会忽略）。
     *
     * <p>带着「哪几列现场为空」的事实一起返回，让页面能如实说明，
     * 而不是让人对着一个空的下拉框猜。
     */
    public record StrategyLogMeta(
            long total,
            /** Status 列的**真实**去重取值（现在是空数组） */
            List<String> statuses,
            /** Status 列是否整列为空 */
            boolean statusColumnEmpty,
            /** FromIP / FromMac 是否整列为空 */
            boolean deviceColumnsEmpty) {
    }

    /** 列表返回（行 + 元信息） */
    public record StrategyLogPage(
            List<StrategyLogView> list,
            long total,
            long page,
            long pageSize,
            StrategyLogMeta meta) {
    }

    /** 查询参数（与前端 {@code currentParams()} 一致） */
    public static class StrategyLogQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        /** 状态。⚠️ 现场 Status 整列为 NULL，传任何值都查不出结果 */
        private String status;
        /** 来源 IP。⚠️ 现场 FromIP 整列为 NULL */
        private String fromIp;
        /** 时间范围（OccurTIme 是 date，传日期或日期时间都可以） */
        private String from;
        private String to;
        private String sortBy;
        private String sortOrder;

        public int pageOrDefault() {
            return page == null || page < 1 ? 1 : page;
        }

        public int pageSizeOrDefault() {
            if (pageSize == null || pageSize < 1) {
                return 15;
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

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getFromIp() {
            return fromIp;
        }

        public void setFromIp(String fromIp) {
            this.fromIp = fromIp;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
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
