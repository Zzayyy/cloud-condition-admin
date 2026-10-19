package com.qianlong.cloud.admin.dto;

import java.util.List;

/**
 * 委托单/成交模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 的
 * {@code OrderRecord} / {@code TradeRecord} 逐一对齐。
 *
 * <p>注意两边的**大小写不同且都是有意的**：
 * <ul>
 *   <li>{@code OrderRecord} 用大写 {@code ID} / {@code BSType}（照 {@code T_Order} 列名）；</li>
 *   <li>{@code TradeRecord} 用小写 {@code id}（照 {@code T_Trade} 列名）。</li>
 * </ul>
 * 两张既有表本来就一个用 {@code ID} 一个用 {@code id}，前端照搬了它们。
 * 所以**不能**用统一的命名策略，必须逐字段显式写。
 */
public final class TradeDtos {

    private TradeDtos() {
    }

    /**
     * 委托单，对应前端 {@code OrderRecord}。
     *
     * <p>⚠️ {@code OrderTime} 是 {@code date} 类型，只有日期没有时分秒，
     * 格式为 {@code yyyy-MM-dd}（见 OrderService 的说明）。
     */
    public record OrderView(
            Long ID,
            Long StrategyID,
            String UserAccount,
            String Account,
            String Code,
            String Market,
            Double OrderPrice,
            Double OrderVolumn,
            Integer BSType,
            Integer OCType,
            String OrderTime,
            String OrderParam,
            Integer OrderStatue,
            String OrderNo,
            String CodeName,
            String StatueDis,
            String Reason) {
    }

    /**
     * 成交，对应前端 {@code TradeRecord}。
     *
     * <p>⚠️ {@code Volumn} 在库里是 {@code varchar}，按字符串返回
     * （前端契约也是 {@code string | null}），不擅自转数字。
     */
    public record TradeView(
            Long id,
            Long StrategyID,
            String OrderNo,
            String TradeNO,
            String Account,
            String Market,
            String Code,
            String Volumn,
            Double Price,
            String TradeTime,
            String UserAccount) {
    }

    /** 买卖方向分布（前端买卖分布图用） */
    public record BsCountRow(Integer bsType, String name, long count) {
    }

    /** 按天下单量（趋势图用） */
    public record DailyOrderRow(String date, long count) {
    }

    /**
     * 委托单汇总（**全量口径**，卡片用）。
     *
     * <p>原先卡片是对当前页 {@code list} 做 filter / reduce 算的：
     * 第 1 页「买入委托 3 笔」、翻到第 2 页变成「2 笔」，而标签上写的是
     * 「买入委托」，会被读成全量。这里按当前筛选条件统计全表。
     *
     * <p>{@code totalCount} 应与列表 {@code total} 一致。
     */
    public record OrderStats(
            /** BSType = 1 的笔数 */
            long buyCount,
            /** BSType = 2 的笔数 */
            long sellCount,
            /** Reason 非空的笔数（异常） */
            long abnormalCount,
            /** OrderPrice × OrderVolumn 求和 */
            double totalAmount,
            /** 当前筛选下的总笔数（应与列表 total 一致） */
            long totalCount) {
    }

    /**
     * 成交汇总（**全量口径**，卡片用）。
     *
     * <p>⚠️ {@code totalVolumn} / {@code totalAmount} 是后端把 varchar 的
     * {@code Volumn} 转 DECIMAL 后求和的**权威值**；前端不要再拿当前页自己加。
     * {@code avgPrice} 由后端用「总额 / 总量」算，**不是**对每页求均价的平均
     * （后者在页大小不同、或每笔量差异大时会明显失真）。
     */
    public record TradeStats(
            /** 成交笔数 */
            long totalCount,
            /** 成交总量 */
            double totalVolumn,
            /** 成交金额 */
            double totalAmount,
            /** 成交均价 = totalAmount / totalVolumn（总量为 0 时为 0） */
            double avgPrice) {
    }

    /** 按合约的成交量（全量口径，合约分布图用） */
    public record CodeCountRow(String code, double count) {
    }

    /**
     * 按天的成交笔数与金额（趋势图，**全量口径**）。
     *
     * <p>{@code amount} 由后端把 varchar 的 {@code Volumn} 转 DECIMAL 后求和，
     * 前端不要再拿明细自己算。
     */
    public record TradeDailyRow(String date, long count, double amount) {
    }

    /**
     * 列表附加信息（非前端契约字段，前端会忽略）。
     *
     * <p>让排查一眼可见「这次查的是哪张表、命中多少行」——
     * 现场 {@code T_Order} 是 0 行、{@code T_Order_his} 只有 1 行，
     * 有了这两个字段就能立刻确认「是表里确实没数据」，而不是接口写错了。
     */
    public record TradeListMeta(String table, String label, long tableRows) {
    }

    /** 委托列表返回（行 + 元信息） */
    public record OrderPage(
            List<OrderView> list,
            long total,
            long page,
            long pageSize,
            TradeListMeta meta) {
    }

    /** 成交列表返回（行 + 元信息） */
    public record TradePage(
            List<TradeView> list,
            long total,
            long page,
            long pageSize,
            TradeListMeta meta) {
    }

    /**
     * 委托单查询参数（与前端 {@code currentParams()} 一致）。
     *
     * <p>{@code table} 由前端传真实表名（{@code T_Order} / {@code T_Order_his}），
     * 后端用 {@code TradeTable} 枚举严格校验 —— 这个字符串来自用户可点的 tab，
     * 绝不能被信任。
     */
    public static class OrderQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        /** 买卖方向 */
        private Integer bsType;
        /** 开平类型 */
        private Integer ocType;
        /** 合约代码（前端单独一个输入框） */
        private String code;
        /** 表名：T_Order / T_Order_his */
        private String table;
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

        public Integer getBsType() {
            return bsType;
        }

        public void setBsType(Integer bsType) {
            this.bsType = bsType;
        }

        public Integer getOcType() {
            return ocType;
        }

        public void setOcType(Integer ocType) {
            this.ocType = ocType;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
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

    /** 成交查询参数（成交回报页用） */
    public static class TradeQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        private String code;
        /** 表名：T_Trade / T_Trade_his */
        private String table;
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

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
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
