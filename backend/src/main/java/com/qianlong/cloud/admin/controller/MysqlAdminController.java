package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlTableSchemaView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlTableView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.ReadonlyQueryResult;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.MysqlAdminService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 数据库管理接口（{@code /api/storage/mysql/*}）。
 *
 * <p>与前端 {@code storageApi} 的 MySQL 部分对应：表清单、表结构、分页读表、只读 SQL。
 *
 * <h2>⚠️ 本模块是全项目最高危的地方</h2>
 * 现场的应用数据库账号是 {@code GRANT ALL PRIVILEGES ON qianlong_cloud.*} ——
 * 一条没被拦住的 SQL 就能删表。而实测发现**数据库自己的只读事务挡不住 DDL**
 * （{@code CREATE TABLE} 会隐式提交逃逸）。所以：
 * <ul>
 *   <li><b>应用层白名单</b>（{@code ReadOnlySqlGuard}）是主要防线：只放行
 *       {@code SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN}，
 *       拒绝多语句与危险关键字；</li>
 *   <li>数据库只读事务 + 结果集上限作为第二、三层；</li>
 *   <li>只读 SQL 与分页读表都要求 <b>{@code action:storage-mysql:readonly-sql}</b> 权限点，
 *       且**执行动作本身写审计**（谁、什么时候、跑了什么 SQL、返回多少行）。</li>
 * </ul>
 *
 * <p>权限点不是摆设：现场 {@code ops} 角色有它、{@code viewer} 没有 ——
 * 「能看页面」与「能跑任意 SELECT」是两件事。
 *
 * <p>⚠️ 本模块**没有任何写接口**，不提供「导出」也不提供「执行任意 SQL 的结果下载」——
 * 导出会把整表数据带离现场，那需要单独的权限设计与审计，不在本次范围。
 */
@RestController
@RequestMapping("/storage/mysql")
@RequiredArgsConstructor
public class MysqlAdminController {

    /** 菜单权限：数据库管理页（表清单与表结构都要求它） */
    private static final String MENU_STORAGE_MYSQL = "menu:storage-mysql";

    /** 只读 SQL 的权限点（已在 T_AdminRolePermission 里存在） */
    private static final String PERM_READONLY_SQL = "action:storage-mysql:readonly-sql";

    private final MysqlAdminService mysqlAdminService;
    private final AuditService auditService;

    /**
     * 表/视图清单。
     *
     * <p>要求 {@code menu:storage-mysql} —— 表名与字段同样是内部信息，
     * 不能靠「藏菜单」来挡。
     */
    @SaCheckPermission(MENU_STORAGE_MYSQL)
    @GetMapping("/tables")
    public ApiResult<List<MysqlTableView>> tables(@RequestParam String database) {
        return ApiResult.ok(mysqlAdminService.listTables(database));
    }

    /** 单表结构（字段、索引、DDL 原文） */
    @SaCheckPermission(MENU_STORAGE_MYSQL)
    @GetMapping("/schema")
    public ApiResult<MysqlTableSchemaView> schema(@RequestParam String database,
                                                  @RequestParam String table) {
        return ApiResult.ok(mysqlAdminService.getTableSchema(database, table));
    }

    /**
     * 分页读一张表的数据。
     *
     * <p>要求 {@code readonly-sql} 权限点：虽然只是「看一张表」，
     * 但它等于对整个库的任意表做 SELECT，和跑只读 SQL 是同一档能力。
     */
    @SaCheckPermission({MENU_STORAGE_MYSQL, PERM_READONLY_SQL})
    @GetMapping("/query")
    public ApiResult<ReadonlyQueryResult> queryTable(@RequestParam String database,
                                                     @RequestParam String table,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer pageSize) {
        return ApiResult.ok(mysqlAdminService.queryTable(database, table, page, pageSize));
    }

    /**
     * 执行一条只读 SQL。
     *
     * <p>与 {@code queryTable} 用同一个路径（前端就是这么定义的：
     * {@code POST /storage/mysql/query} 传 {@code sql}），
     * 靠 HTTP 方法区分：GET 按表分页、POST 跑 SQL。
     *
     * <p><b>必写审计</b>：跑任意 SELECT 是能带走全表数据的能力，
     * 必须留痕（谁、什么 SQL、返回多少行、耗时）。SQL 文本会记进审计，
     * 但**不记结果内容** —— 审计是「谁做了什么」，不是数据副本。
     */
    @SaCheckPermission({MENU_STORAGE_MYSQL, PERM_READONLY_SQL})
    @PostMapping("/query")
    public ApiResult<ReadonlyQueryResult> runSql(@CurrentAdminUser AdminPrincipal me,
                                                 @RequestBody SqlQueryRequest body,
                                                 HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        String target = body == null ? null : abbreviate(body.sql());
        try {
            ReadonlyQueryResult result = mysqlAdminService.runReadonlyQuery(
                    body == null ? null : body.database(),
                    body == null ? null : body.sql(),
                    body == null ? null : body.limit());
            auditService.record(me.username(), PERM_READONLY_SQL, target, true,
                    "只读 SQL 返回 " + result.list().size() + " 行"
                            + (result.truncated() ? "（已截断）" : "")
                            + "，耗时 " + result.costMs() + "ms",
                    ip, AuditService.costOf(start));
            return ApiResult.ok(result);
        } catch (RuntimeException ex) {
            // 被白名单拒绝也算一次尝试，同样要留痕
            auditService.record(me.username(), PERM_READONLY_SQL, target, false,
                    ex.getMessage(), ip, AuditService.costOf(start));
            throw ex;
        }
    }

    /** 只读 SQL 请求体（与前端 {@code runReadonlyQuery(database, sql, limit)} 对应） */
    public record SqlQueryRequest(String database, String sql, Integer limit) {
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }
}
