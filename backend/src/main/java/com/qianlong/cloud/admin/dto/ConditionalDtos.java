package com.qianlong.cloud.admin.dto;

import java.util.List;

/**
 * 条件单模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 的
 * {@code Conditional} 逐一对齐（**首字母大写**，因为前端直接照搬了 MySQL 列名）。
 *
 * <p>不能靠全局命名策略转换，必须逐字段显式写 —— 这正是用 DTO 而不是
 * 直接返回 Entity 的价值。
 */public final class ConditionalDtos {

    private ConditionalDtos() {
    }

    /**
     * 条件单，对应前端 {@code Conditional}。
     *
     * <p>字段名与 {@code T_Conditional} 的列名一致（首字母大写）。
     *
     * <p><b>数据来源有两个</b>（2026-10-21 起）：
     * <ul>
     *   <li>{@code redis} —— Redis {@code strategy-*} 键里的<b>实时态</b>。
     *       现场真实条件单都在这里（60 条），MySQL 那三张表是 0 行。</li>
     *   <li>{@code db} —— MySQL {@code T_Conditional} / {@code T_Conditional_his}
     *       里的<b>归档</b>。</li>
     * </ul>
     * 所以每行都带 {@code source}，让页面能如实显示"这条是从哪读到的" ——
     * 两边都有同一条时以 Redis 为准（实时态），但仍标出来，不做静默合并。
     */
    public record ConditionalView(
            Long id,
            String Account,
            String UserAccount,
            /** 条件单原始内容（JSON 字符串），页面会格式化展示 */
            String Strategy,
            String AddTime,
            String RunTime,
            String CloseTime,
            Integer RunCount,
            String BeginTime,
            String EndTime,
            /** 触发状态：0 未触发，1 已触发 */
            Integer Status,
            /** 有效标志：0 有效，1 暂停，2 删除 */
            Integer Flag,
            String Description,
            /** 有效期，0 为长期有效 */
            Integer Validity,
            String ModifyTime,
            String TriggeredTime,
            String Reason,
            /** 云单状态：0 停止，1 运行，2 失效，3 结束 */
            Integer Mode,
            /** 云条件单类型：0 保留，1 开仓，2 平仓，3 按合约止盈止损，4 按标的止盈止损 */
            Integer Type,
            String Name,
            /** 数据来源：redis（实时态）/ db（MySQL 归档） */
            String source,
            /** 来源实例，如 {@code 192.168.1.136/db1}（MySQL 来源为 null） */
            String sourceInstance,
            /** 策略服务器编号（Redis 侧才有；现场全是 -1，即"尚未分配"） */
            Integer strategyServerID,
            /** 同一个单号在几个实例上各存了一份 */
            int copies) {
    }

    /** 按天新增趋势（前端 mockDailyCreated 的同口径） */
    public record DailyCreatedRow(String date, long count) {
    }

    /** 条件单类型分布（前端可由当前页算出，后端也提供一个全量口径） */
    public record TypeCountRow(Integer type, String name, long count) {
    }

    /**
     * 条件单汇总（**全量口径**，卡片用）。
     *
     * <p>为什么要有它：卡片原先是对当前页 {@code list} 做 filter 算的，
     * 翻一页数字就变一次，而标签写的是「运行中」这种像全量的说法。
     * 这个接口按当前**筛选条件**统计全表，与列表 total 同源。
     *
     * <p>{@code totalCount} 与列表 {@code total} 应当相等 ——
     * 不相等就说明两处筛选条件写岔了，联调时可直接据此发现。
     */
    public record ConditionalStats(
            /** Mode = 1 的条数 */
            long runningCount,
            /** Status = 1 的条数 */
            long triggeredCount,
            /** Flag = 1 的条数 */
            long pausedCount,
            /** RunCount 求和 */
            long totalRunCount,
            /** 当前筛选下的总条数（应与列表 total 一致） */
            long totalCount) {
    }

    /**
     * 列表附加信息（非前端契约字段，前端会忽略）。
     *
     * <p>用途是让排查一眼可见「这次读的是哪边、命中多少行」——
     * 现在数据来自两个地方（Redis 实时态 + MySQL 归档），
     * 有了 {@code redisCount} / {@code dbOnlyCount} 就能立刻确认
     * 「这 60 条全在实时态、还没落库」这个事实，而不是靠猜。
     */
    public record ConditionalListMeta(
            /** 数据源标识：running / history / all */
            String source,
            /** 该数据源的中文说明 */
            String label,
            /** 筛选口径说明（这个 tab 到底筛了什么） */
            String scope,
            /** 命中行数（等于 total，保留是为了排查时更直观） */
            long tableRows,
            /** 其中来自 Redis 实时态的条数 */
            long redisCount,
            /** 其中只存在于 MySQL 归档的条数 */
            long dbOnlyCount,
            /** 是否因为超过内存上限而被截断（截断了就<b>如实标注</b>，不静默丢数据） */
            boolean truncated) {
    }

    /** 列表返回：行 + 元信息 */
    public record ConditionalPage(
            List<ConditionalView> list,
            long total,
            long page,
            long pageSize,
            ConditionalListMeta meta) {
    }

    /** 查询参数（与前端 {@code currentParams()} 一致） */
    public static class ConditionalQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        private Integer type;
        private Integer mode;
        /** 数据源：running / history / all */
        private String source;
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

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }

        public Integer getMode() {
            return mode;
        }

        public void setMode(Integer mode) {
            this.mode = mode;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
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
