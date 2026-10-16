package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditActionOption;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditDailyRow;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditExportResult;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditLogView;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditQuery;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditStats;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginDailyRow;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogQuery;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogView;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginStats;
import com.qianlong.cloud.admin.dto.SystemDtos.NameValueRow;
import com.qianlong.cloud.admin.dto.SystemDtos.RangeQuery;
import com.qianlong.cloud.admin.service.AuditQueryService;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.LoginLogService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 操作审计与管理端登录日志接口（{@code /api/system/*-logs}）。
 *
 * <p>目录划分与前端 {@code adminApi} 一致：
 * <ul>
 *   <li>{@code /system/audit-logs}：操作审计（{@code T_AdminAudit}）与两个聚合；</li>
 *   <li>{@code /system/login-logs}：<b>管理端</b>登录日志（{@code T_AdminLoginLog}），
 *       不是云单客户的 {@code T_UserLog}。</li>
 * </ul>
 *
 * <p>读取不要求 {@code manage} 权限点（只读观察者也该能看审计），
 * 但<b>导出</b>要求 {@code action:system-audit:export} —— 导出即离场，需留痕。
 */
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
public class AuditController {

    private static final String ACTION_EXPORT = "action:system-audit:export";

    private final AuditQueryService auditQueryService;
    private final LoginLogService loginLogService;
    private final AuditService auditService;

    /* ============================== 操作审计 ============================== */

    /** 审计明细（分页 + 多条件筛选 + 时间范围 + 排序） */
    @SaCheckLogin
    @GetMapping("/audit-logs")
    public ApiResult<PageResult<AuditLogView>> listAudit(AuditQuery query) {
        return ApiResult.ok(auditQueryService.listAuditLogs(query));
    }

    /**
     * 审计动作字典（供筛选下拉）。
     *
     * <p>动作键与前端 {@code src/config/permissions.ts} 同源；
     * 另含 {@code system:login} / {@code system:connection} 两个登记型动作。
     */
    @SaCheckLogin
    @GetMapping("/audit-logs/actions")
    public ApiResult<List<AuditActionOption>> auditActions() {
        return ApiResult.ok(auditService.actions());
    }

    /** 审计按天聚合（高危 / 其他）；未传 from/to 时默认最近 14 个自然日 */
    @SaCheckLogin
    @GetMapping("/audit-logs/daily")
    public ApiResult<List<AuditDailyRow>> auditDaily(RangeQuery query) {
        return ApiResult.ok(auditQueryService.auditDaily(query));
    }

    /** 审计按动作聚合（Top 8） */
    @SaCheckLogin
    @GetMapping("/audit-logs/by-action")
    public ApiResult<List<NameValueRow>> auditByAction(RangeQuery query) {
        return ApiResult.ok(auditQueryService.auditByAction(query));
    }

    /**
     * 审计汇总（**全量口径**，卡片用）。
     *
     * <p>与 {@code /audit-logs} 列表同筛选条件，{@code totalCount} 应等于列表 {@code total}。
     */
    @SaCheckLogin
    @GetMapping("/audit-logs/stats/summary")
    public ApiResult<AuditStats> auditStats(AuditQuery query) {
        return ApiResult.ok(auditQueryService.auditStats(query));
    }

    /**
     * 管理端登录日志汇总（**全量口径**，卡片用）。
     *
     * <p>与 {@code /login-logs} 列表同筛选条件。
     */
    @SaCheckLogin
    @GetMapping("/login-logs/stats/summary")
    public ApiResult<LoginStats> loginStats(LoginLogQuery query) {
        return ApiResult.ok(auditQueryService.loginStats(query));
    }

    /* ============================== 管理端登录日志 ============================== */

    /** 登录日志明细（分页 + 账号 / IP / 结果 / 时间范围筛选） */
    @SaCheckLogin
    @GetMapping("/login-logs")
    public ApiResult<PageResult<LoginLogView>> listLoginLogs(LoginLogQuery query) {
        return ApiResult.ok(loginLogService.listLogs(query));
    }

    /** 登录成功 / 失败按天聚合 */
    @SaCheckLogin
    @GetMapping("/login-logs/daily")
    public ApiResult<List<LoginDailyRow>> loginDaily(RangeQuery query) {
        return ApiResult.ok(auditQueryService.loginDaily(query));
    }

    /** 登录失败按来源 IP 聚合（Top 8） */
    @SaCheckLogin
    @GetMapping("/login-logs/fail-by-ip")
    public ApiResult<List<NameValueRow>> loginFailByIp(RangeQuery query) {
        return ApiResult.ok(auditQueryService.loginFailByIp(query));
    }

    /* ============================== 导出 ============================== */

    /**
     * 导出审计日志。
     *
     * <p>前端要求「必须由后端出文件」：明细接口有分页与条数上限，
     * 前端把当前页导出成 CSV 会让人以为拿到了全量（前端 README 第 9 条）。
     *
     * <p><b>关于 url：</b>本接口在服务端生成 CSV 文件并返回可直接下载的相对地址
     * （{@code /api/system/audit-logs/export/download?file=...}），
     * 不使用 {@code file://} 之类的本地路径 —— 那种地址浏览器根本打不开，
     * 前端只会拿到一个「看着成功、下载失败」的结果。
     *
     * <p>导出动作本身写审计（{@code action:system-audit:export}），导出即离场，需留痕。
     */
    @SaCheckPermission(ACTION_EXPORT)
    @PostMapping("/audit-logs/export")
    public ApiResult<AuditExportResult> exportAudit(@CurrentAdminUser AdminPrincipal me,
                                                    @RequestBody(required = false) AuditQuery query,
                                                    HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        AuditQuery effective = query == null ? new AuditQuery() : query;
        try {
            AuditQueryService.ExportRows rows = auditQueryService.exportRows(effective);
            if (rows.rows().isEmpty()) {
                // 没有数据就不生成任何文件，如实回 0 条（前端据此提示，不会产生空文件误导人）
                auditService.success(me.username(), ACTION_EXPORT, "无数据", "当前筛选条件下无可导出记录", ip, start);
                return ApiResult.ok(new AuditExportResult(null, null, 0));
            }
            var file = CsvExporter.writeAuditCsv(rows.rows());
            String message = "导出 " + rows.rows().size() + " 条，格式 CSV";
            if (rows.truncated()) {
                // 如实说明被截断，绝不把截断后的结果当全量交付
                message += "（已达上限 " + auditQueryService.exportLimit() + " 条，结果被截断）";
            }
            auditService.success(me.username(), ACTION_EXPORT, file.filename(), message, ip, start);
            return ApiResult.ok(new AuditExportResult(file.url(), file.filename(), rows.rows().size()));
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_EXPORT, "导出失败", ex.getMessage(), ip, start);
            throw ex;
        }
    }

    /**
     * 下载已生成的导出文件。
     *
     * <p>文件名只允许 {@code [A-Za-z0-9._-]}（由 {@link CsvExporter} 校验），
     * 从根上杜绝 {@code ../} 路径穿越 —— 这是文件下载接口最常见的漏洞。
     */
    @SaCheckPermission(ACTION_EXPORT)
    @GetMapping("/audit-logs/export/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>
    downloadAuditExport(@RequestParam("file") String file) {
        return CsvExporter.readExport(file);
    }
}
