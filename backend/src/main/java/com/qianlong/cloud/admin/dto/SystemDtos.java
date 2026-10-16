package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 系统管理模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 逐一对齐。
 *
 * <p>这里刻意不直接把 Entity 当出参：
 * <ul>
 *   <li>{@code AdminUserEntity.passwordHash} <b>绝不能出现在响应里</b>，
 *       用独立 DTO 从类型上杜绝（比「记得加 @JsonIgnore」可靠）；</li>
 *   <li>数据库列名是 {@code description}，而前端字段是 {@code desc}，需要一层映射。</li>
 * </ul>
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    /* ----------------------------- 角色 ----------------------------- */

    /**
     * 角色出参，对应前端 {@code AdminRole}。
     *
     * <p>{@code userCount} 由后端聚合（前端只读），用于界面上提示
     * 「删掉这个角色会影响几个人」。
     */
    public record AdminRoleView(
            Integer id,
            String code,
            String name,
            String desc,
            boolean builtin,
            List<String> permissions,
            long userCount,
            String createdAt,
            String updatedAt) {
    }

    /** 角色入参，对应前端 {@code AdminRolePayload}。 */
    public record AdminRolePayload(
            @NotBlank(message = "角色编码不能为空")
            @Pattern(regexp = "^[a-z][a-z0-9_]{1,31}$",
                    message = "角色编码需以小写字母开头，只能含小写字母、数字与下划线，长度 2~32")
            String code,

            @NotBlank(message = "角色名称不能为空")
            @Size(max = 32, message = "角色名称最长 32 个字符")
            String name,

            @Size(max = 200, message = "说明最长 200 个字符")
            String desc,

            @NotEmpty(message = "至少选择一个权限点：一个权限都没有的角色，登录后什么都看不到")
            List<String> permissions) {
    }

    /* ----------------------------- 账号 ----------------------------- */

    /** 账号出参，对应前端 {@code AdminUser}。**没有密码字段**。 */
    public record AdminUserView(
            Integer id,
            String username,
            String displayName,
            List<Integer> roleIds,
            boolean enabled,
            boolean locked,
            int failedAttempts,
            boolean mustChangePassword,
            String lastLoginTime,
            String lastLoginIP,
            int loginCount,
            String createdAt,
            String updatedAt,
            String remark) {
    }

    /**
     * 账号入参，对应前端 {@code AdminUserPayload}。
     *
     * <p>{@code password} 是<b>仅新建时必填</b>；编辑时留空表示不改密码
     * （改密要走独立的「重置密码」接口，后端要单独写审计）。
     * 因此这里不能加 {@code @NotBlank} —— 该规则在 service 里按新建/编辑分别判定。
     */
    public record AdminUserPayload(
            @NotBlank(message = "登录名不能为空")
            @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$",
                    message = "登录名 3-32 位，仅限字母、数字与 _ . -")
            String username,

            @Size(max = 32, message = "显示名最长 32 个字符")
            String displayName,

            @NotEmpty(message = "至少选择一个角色")
            List<Integer> roleIds,

            @NotNull(message = "启用状态不能为空")
            Boolean enabled,

            @Size(max = 255, message = "备注最长 255 个字符")
            String remark,

            String password) {
    }

    /** 启用/停用入参 */
    public record EnabledPayload(@NotNull(message = "enabled 不能为空") Boolean enabled) {
    }

    /**
     * 重置密码入参。
     *
     * <p>{@code mustChange = true} 时同时把账号标记为「下次登录强制改密」
     * （前端 {@code resetPasswordAndForceChange}）。
     */
    public record ResetPasswordPayload(
            @NotBlank(message = "新密码不能为空") String password,
            Boolean mustChange) {
    }

    /** 修改自己的密码入参 */
    public record ChangePasswordPayload(
            @NotBlank(message = "原密码不能为空") String oldPassword,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    /* --------------------------- 密码策略 --------------------------- */

    /** 密码策略出参/入参，对应前端 {@code PasswordPolicy}。 */
    public record PasswordPolicyDto(
            Integer minLength,
            Boolean requireUpper,
            Boolean requireLower,
            Boolean requireDigit,
            Boolean requireSymbol,
            Integer maxFailedAttempts,
            Integer lockMinutes,
            Integer expireDays,
            Boolean forceChangeOnFirstLogin,
            Integer historySize) {
    }

    /* --------------------------- 登录鉴权 --------------------------- */

    /** 登录入参 */
    public record LoginPayload(
            @NotBlank(message = "请输入登录名") String username,
            @NotBlank(message = "请输入密码") String password) {
    }

    /** 登录成功返回，对应前端 {@code LoginResult} */
    public record LoginResult(
            String token,
            String username,
            String displayName,
            List<String> roles,
            List<String> permissions,
            Boolean mustChangePassword) {
    }

    /** 当前登录人资料，对应前端 {@code authApi.profile()} 的返回 */
    public record ProfileResult(
            String username,
            String displayName,
            List<String> roles,
            List<String> permissions,
            Boolean mustChangePassword) {
    }

    /* --------------------------- 审计与登录日志 --------------------------- */

    /** 审计出参，对应前端 {@code AuditLog} */
    public record AuditLogView(
            Long id,
            String operator,
            String action,
            String actionLabel,
            String module,
            String level,
            String target,
            String result,
            String message,
            String ip,
            Integer costMs,
            String createdAt) {
    }

    /** 审计动作字典项，对应前端 {@code AuditActionOption} */
    public record AuditActionOption(
            String action,
            String label,
            String module,
            String level) {
    }

    /** 审计按天聚合行，对应前端 {@code AuditDailyRow} */
    public record AuditDailyRow(String date, long high, long other) {
    }

    /** 登录日志出参，对应前端 {@code AdminLoginLog} */
    public record LoginLogView(
            Long id,
            String username,
            String loginTime,
            String loginIP,
            String loginOS,
            boolean success,
            String info) {
    }

    /** 登录按天聚合行，对应前端 {@code LoginDailyRow} */
    public record LoginDailyRow(String date, long success, long failure) {
    }

    /** 通用「名称 → 计数」聚合行，对应前端 {@code NameValueRow} */
    public record NameValueRow(String name, long value) {
    }

    /**
     * 管理账号汇总（**全量口径**，卡片用）。
     *
     * <p>与列表同筛选条件，所以 {@code totalCount} 应等于列表 {@code total}。
     */
    public record AdminUserStats(
            long totalCount,
            long enabledCount,
            long disabledCount,
            long lockedCount) {
    }

    /**
     * 操作审计汇总（**全量口径**，卡片用）。
     *
     * <p>与列表同筛选条件，{@code totalCount} 应等于列表 {@code total}。
     */
    public record AuditStats(
            /** level = 'high' 的条数 */
            long highCount,
            /** result = 'failure' 的条数 */
            long failureCount,
            /** 去重后的操作人数 */
            long operatorCount,
            long totalCount) {
    }

    /** 管理端登录日志汇总（**全量口径**，卡片用） */
    public record LoginStats(
            long successCount,
            long failureCount,
            long totalCount) {
    }

    /** 审计导出结果，对应前端 {@code exportAuditLogs} 的返回 */
    public record AuditExportResult(String url, String filename, long total) {
    }

    /* ------------------------------ 查询参数 ------------------------------ */

    /**
     * 分页与筛选基类。
     *
     * <p><b>为什么 {@code page}/{@code pageSize} 是可空的包装类型：</b>
     * 前端在没有任何筛选时可能一个参数都不传，用 {@code int} 会变成 0 页；
     * 用包装类型可以区分「没传」与「传了 0」，后者才按默认值兜底。
     */
    public static class PageQuery {

        private Integer page;
        private Integer pageSize;
        private String keyword;
        private String sortBy;
        private String sortOrder;
        /** 时间范围：{@code yyyy-MM-dd HH:mm:ss} 字符串，语义是 [from, to] 闭区间 */
        private String from;
        private String to;

        public int pageOrDefault() {
            return page == null || page < 1 ? 1 : page;
        }

        /** 上限 200：前端列表页最多 60，导出另有独立接口，防止有人手拼 pageSize=10 万 */
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

    /** 审计列表查询参数 */
    public static class AuditQuery extends PageQuery {
        private String level;
        private String module;
        private String action;
        private String result;
        private String operator;

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }
    }

    /** 登录日志列表查询参数 */
    public static class LoginLogQuery extends PageQuery {
        /**
         * 是否成功。
         *
         * <p>⚠️ 前端这里传的是字符串 {@code 'true'} / {@code 'false'}（不是审计那套
         * {@code 'success'}/{@code 'failure'}）。用 {@link Boolean} 让 Spring 自己转换，
         * 传了无法识别的值会直接报参数错误，而不是**静默不过滤**。
         */
        private Boolean success;
        private String username;
        private String ip;

        public Boolean getSuccess() {
            return success;
        }

        public void setSuccess(Boolean success) {
            this.success = success;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }
    }

    /** 账号列表查询参数 */
    public static class UserQuery extends PageQuery {
        private Integer roleId;
        /** enabled / disabled / locked，与前端下拉取值一致 */
        private String status;

        public Integer getRoleId() {
            return roleId;
        }

        public void setRoleId(Integer roleId) {
            this.roleId = roleId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** 聚合接口参数（只有时间范围） */
    public static class RangeQuery {
        private String from;
        private String to;

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
}
