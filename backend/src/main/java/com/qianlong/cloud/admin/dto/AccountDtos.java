package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 账户与用户模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 逐一对齐。
 *
 * <p>注意这里的字段名**故意不统一**：
 * <ul>
 *   <li>{@code AccountRow} 用小驼峰（{@code account} / {@code useraccount}）——
 *       它前端就是这么定义的，且它是「合并行」不是表映射；</li>
 *   <li>{@code UserLogView} 用首字母大写（{@code Account} / {@code LogTime}）——
 *       前端 {@code UserLog} 直接照搬了 MySQL 列名，必须跟它一致。</li>
 * </ul>
 * 所以**不能**靠一个全局命名策略去转换，必须逐字段显式写。
 * 这正是「用 DTO 而不是直接返回 Entity」的价值。
 */
public final class AccountDtos {

    private AccountDtos() {
    }

    /* ============================ 用户账号（合并行） ============================ */

    /**
     * 用户账号合并行，对应前端 {@code AccountRow}。
     *
     * <p>MySQL（{@code T_User}）与 Redis（{@code acc-*}）两边字段有重叠也有差异，
     * 合并逻辑放后端做（前端不拼两个接口）：
     * <ul>
     *   <li>两边都有的字段（name / regtime / tradeAccount …）**以 Redis 为准** ——
     *       Redis 是实时态，MySQL 那行可能很久没更新（现场 T_User 的
     *       LoginTime 停在 2026-09-15，而 Redis 里是活的）；</li>
     *   <li>{@code hasRedis} 明确告诉页面「这个账号在 Redis 侧没有实时对象」，
     *       而不是把 Redis 字段静默填成 null 让人以为是空值。</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AccountRow(
            String account,
            String useraccount,
            String name,
            Integer accounttype,
            Integer accatt,
            String tradeAccount,
            String branchNo,
            String regtime,
            String logintime,
            String loginIP,
            String loginMAC,
            String loginOS,
            List<ShareholderDto> shareHolders,
            boolean signed,
            boolean hasRedis,
            Integer onlineflag,
            Object syncflag,
            Integer strategyserverid,
            TraderServerDto traderserver,
            Integer userId,
            /** 该账号的 Redis 实时态是从哪个实例/db 读到的（现场多实例，排查要用） */
            String redisInstance) {
    }

    /** 股东号 */
    public record ShareholderDto(String SAccount, Integer ExchangeNum) {
    }

    /** 委托服务器（Redis acc-* 里的 traderserver 对象） */
    public record TraderServerDto(
            Integer id,
            String ip,
            String lastActiveTime,
            Long lastActiveTimeLong,
            Boolean enable) {
    }

    /** Redis {@code acc-*} 键的原始内容，对应前端 {@code RedisAccount} */
    public record RedisAccountView(
            Integer id,
            String useraccount,
            String account,
            Integer accounttype,
            Integer maintype,
            String regtime,
            String logintime,
            String loginIP,
            String loginMAC,
            String loginOS,
            String name,
            String tradeAccount,
            String branchNo,
            String shareHolders,
            Object regprotocal,
            Integer strategyserverid,
            Integer onlineflag,
            Object syncflag,
            TraderServerDto traderserver,
            /** 来源实例（非前端契约字段，便于排查用；前端会忽略多余字段） */
            String instance,
            /** 原始 JSON 文本，供页面「原始内容」tab 展示 */
            String rawJson) {
    }

    /* ================================ 登录日志 ================================ */

    /** 登录日志，对应前端 {@code UserLog}（字段名照 MySQL 列名，首字母大写） */
    public record UserLogView(
            Integer id,
            String Account,
            String LogTime,
            String LogIP,
            String LogMAC,
            String LogOS,
            String Info) {
    }

    /** 登录日志按账号聚合，对应前端 {@code AccountLogStat} */
    public record AccountLogStatView(
            String Account,
            long count,
            String firstTime,
            String lastTime) {
    }

    /* ================================ 查询参数 ================================ */

    /**
     * 用户账号列表查询参数（字段名与前端 {@code currentParams()} 完全一致）。
     *
     * <p>{@code signed} / {@code online} 前端传的是**布尔**，
     * 用 {@link Boolean} 接收 —— 传了无法识别的值会直接报参数错误，
     * 而不是静默不过滤（前端 README 第 7 条记录过这个坑）。
     */
    public static class AccountQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        private Integer accounttype;
        private Integer accatt;
        private Boolean signed;
        private Boolean online;
        private String branchNo;
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

        public Integer getAccounttype() {
            return accounttype;
        }

        public void setAccounttype(Integer accounttype) {
            this.accounttype = accounttype;
        }

        public Integer getAccatt() {
            return accatt;
        }

        public void setAccatt(Integer accatt) {
            this.accatt = accatt;
        }

        public Boolean getSigned() {
            return signed;
        }

        public void setSigned(Boolean signed) {
            this.signed = signed;
        }

        public Boolean getOnline() {
            return online;
        }

        public void setOnline(Boolean online) {
            this.online = online;
        }

        public String getBranchNo() {
            return branchNo;
        }

        public void setBranchNo(String branchNo) {
            this.branchNo = branchNo;
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

    /**
     * 登录日志查询参数（与前端 {@code logParams()} 一致）。
     *
     * <p>{@code withDevice}：只看有设备信息 / 只看缺设备信息。
     * ⚠️ 现场 {@code LogIP}/{@code LogMAC}/{@code LogOS} **全部为 NULL**，
     * 所以 {@code withDevice=true} 必然查出 0 条 —— 这不是 bug，是真的没有数据。
     * 页面已如实说明这一点。
     */
    public static class LogQuery {
        private Integer page;
        private Integer pageSize;
        private String keyword;
        private String account;
        private String info;
        private Boolean withDevice;
        private String sortBy;
        private String sortOrder;
        /** 时间范围（yyyy-MM-dd HH:mm:ss），语义 [from, to] 闭区间 */
        private String from;
        private String to;

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

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }

        public Boolean getWithDevice() {
            return withDevice;
        }

        public void setWithDevice(Boolean withDevice) {
            this.withDevice = withDevice;
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
    }

    /**
     * 账号列表的附加信息（非前端契约字段，但很有用）。
     *
     * <p>Redis 侧如果有实例连不上，页面应当**知道**这件事 ——
     * 否则「hasRedis 全是 false」会被读成「这些账号都没有实时对象」，
     * 而真实原因可能是「我们压根没连上 Redis」。
     * 所以这里把不可用实例如实带出去。
     */
    public record AccountListMeta(
            List<String> redisInstances,
            List<String> redisUnavailable,
            int mysqlTotal,
            int redisTotal) {
    }
}
