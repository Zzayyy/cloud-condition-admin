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
     * 数据**只来自 MySQL** 的 {@code T_Conditional} / {@code T_Conditional_his} /
     * {@code V_Conditional}。
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
            String Name) {
    }

    /** 按天新增趋势（前端 mockDailyCreated 的同口径） */
    public record DailyCreatedRow(String date, long count) {
    }

    /** 条件单类型分布（前端可由当前页算出，后端也提供一个全量口径） */
    public record TypeCountRow(Integer type, String name, long count) {
    }

    /**
     * 列表附加信息（非前端契约字段，前端会忽略）。
     *
     * <p>用途是让排查一眼可见「这次查的是哪张表、命中多少行」——
     * 现场三张表都是 0 行，有了这两个字段就能立刻确认
     * 「是表里确实没有数据」，而不是「接口/筛选写错了」。
     */
    public record ConditionalListMeta(
            /** 数据源标识：running / history / all */
            String source,
            /** 对应的真实表/视图名 */
            String table,
            /** 该数据源的中文说明 */
            String label,
            /** 该表命中的行数（等于 total，保留是为了排查时更直观） */
            long tableRows) {
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
