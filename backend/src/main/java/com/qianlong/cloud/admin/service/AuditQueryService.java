package com.qianlong.cloud.admin.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditDailyRow;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditLogView;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditQuery;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginDailyRow;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogQuery;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogView;
import com.qianlong.cloud.admin.dto.SystemDtos.NameValueRow;
import com.qianlong.cloud.admin.dto.SystemDtos.RangeQuery;
import com.qianlong.cloud.admin.entity.AdminAuditEntity;
import com.qianlong.cloud.admin.entity.AdminLoginLogEntity;
import com.qianlong.cloud.admin.mapper.AdminAuditMapper;
import com.qianlong.cloud.admin.mapper.AdminLoginLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 操作审计与管理端登录日志的查询与聚合。
 *
 * <p><b>聚合必须由后端做。</b>明细接口是分页的，前端拿明细自己「统计」只能算出当前一页，
 * 会被误读成全量（前端 README 第 9 条点名了这个坑）。
 *
 * <p><b>补零：</b>按天聚合时，区间内<b>没有数据的那天也要返回 0</b>。
 * 若只返回有数据的日期，前端柱状图的 X 轴会把「3 号」和「7 号」画成相邻两根柱子，
 * 看上去像是连续四天都有量 —— 这是会误导判断的静默错误。
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    /** 默认统计窗口：最近 14 个自然日（含今天），与前端 last14DaysRange() 的口径一致 */
    private static final int DEFAULT_DAYS = 14;

    /** 动作分布 / 失败 IP 的 Top N 上限 */
    private static final int TOP_N = 8;

    /** 导出上限：防止「无筛选导出」把 220 万行的表拖垮 */
    private static final int EXPORT_LIMIT = 100_000;

    private final AdminAuditMapper auditMapper;
    private final AdminLoginLogMapper loginLogMapper;

    /* ============================== 审计列表 ============================== */

    public PageResult<AuditLogView> listAuditLogs(AuditQuery query) {
        LambdaQueryWrapper<AdminAuditEntity> wrapper = Wrappers.lambdaQuery();

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            // 前端提示是「目标 / 操作人 / 动作名」，四处都覆盖（含动作键，便于按键搜索）
            wrapper.and(w -> w.like(AdminAuditEntity::getTarget, keyword)
                    .or().like(AdminAuditEntity::getOperator, keyword)
                    .or().like(AdminAuditEntity::getActionLabel, keyword)
                    .or().like(AdminAuditEntity::getAction, keyword));
        }
        applyEq(wrapper, AdminAuditEntity::getLevel, trimToNull(query.getLevel()), "level");
        applyEq(wrapper, AdminAuditEntity::getModule, trimToNull(query.getModule()), "module");
        applyEq(wrapper, AdminAuditEntity::getAction, trimToNull(query.getAction()), "action");
        applyEq(wrapper, AdminAuditEntity::getResult, trimToNull(query.getResult()), "result");
        String operator = trimToNull(query.getOperator());
        if (operator != null) {
            wrapper.like(AdminAuditEntity::getOperator, operator);
        }

        // 时间范围：[from, to] 闭区间，与前端筛选、聚合三处口径完全一致
        LocalDateTime from = TimeParams.parse(query.getFrom(), "from");
        LocalDateTime to = TimeParams.parse(query.getTo(), "to");
        wrapper.ge(from != null, AdminAuditEntity::getCreatedAt, from);
        wrapper.le(to != null, AdminAuditEntity::getCreatedAt, to);

        applyAuditSort(wrapper, query.getSortBy(), query.getSortOrder());

        Page<AdminAuditEntity> page = auditMapper.selectPage(
                Page.of(query.pageOrDefault(), query.pageSizeOrDefault()), wrapper);

        return PageResult.of(page.getRecords().stream().map(AuditQueryService::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 审计排序白名单。
     *
     * <p>前端只给「时间」列开了 {@code sorter}，但白名单仍要显式列全 ——
     * 因为 {@code sortBy} 是查询串参数，任何人都能传。
     */
    private void applyAuditSort(LambdaQueryWrapper<AdminAuditEntity> wrapper, String sortBy, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String field = trimToNull(sortBy);
        if (field == null || "createdAt".equals(field)) {
            wrapper.orderBy(true, asc, AdminAuditEntity::getCreatedAt);
        } else if ("level".equals(field)) {
            wrapper.orderBy(true, asc, AdminAuditEntity::getLevel);
        } else if ("operator".equals(field)) {
            wrapper.orderBy(true, asc, AdminAuditEntity::getOperator);
        } else if ("costMs".equals(field)) {
            wrapper.orderBy(true, asc, AdminAuditEntity::getCostMs);
        } else {
            throw BizException.badRequest("不支持按 " + field + " 排序");
        }
        // 时间相同时用 id 兜底，避免翻页时同一行重复出现或漏掉
        wrapper.orderByDesc(AdminAuditEntity::getId);
    }

    /* ============================== 登录日志列表 ============================== */

    public PageResult<LoginLogView> listLoginLogs(LoginLogQuery query) {
        LambdaQueryWrapper<AdminLoginLogEntity> wrapper = Wrappers.lambdaQuery();

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(AdminLoginLogEntity::getUsername, keyword)
                    .or().like(AdminLoginLogEntity::getLoginIP, keyword)
                    .or().like(AdminLoginLogEntity::getLoginOS, keyword));
        }
        String username = trimToNull(query.getUsername());
        if (username != null) {
            wrapper.like(AdminLoginLogEntity::getUsername, username);
        }
        String ip = trimToNull(query.getIp());
        if (ip != null) {
            wrapper.like(AdminLoginLogEntity::getLoginIP, ip);
        }
        /*
         * 「结果」筛选：前端传的是 'true' / 'false'（字符串），不是审计那套
         * 'success' / 'failure'。这里用 Boolean 接收，Spring 会做转换；
         * 传了无法识别的值会直接报参数错误，而不是**静默不过滤**。
         * 前端 README 第 7 条记录过这个坑：复用审计的取值会导致「永远只显示失败的行」。
         */
        if (query.getSuccess() != null) {
            wrapper.eq(AdminLoginLogEntity::getSuccess, query.getSuccess());
        }

        LocalDateTime from = TimeParams.parse(query.getFrom(), "from");
        LocalDateTime to = TimeParams.parse(query.getTo(), "to");
        wrapper.ge(from != null, AdminLoginLogEntity::getLoginTime, from);
        wrapper.le(to != null, AdminLoginLogEntity::getLoginTime, to);

        applyLoginSort(wrapper, query.getSortBy(), query.getSortOrder());

        Page<AdminLoginLogEntity> page = loginLogMapper.selectPage(
                Page.of(query.pageOrDefault(), query.pageSizeOrDefault()), wrapper);

        return PageResult.of(page.getRecords().stream().map(AuditQueryService::toView).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    private void applyLoginSort(LambdaQueryWrapper<AdminLoginLogEntity> wrapper, String sortBy, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String field = trimToNull(sortBy);
        if (field == null || "loginTime".equals(field)) {
            wrapper.orderBy(true, asc, AdminLoginLogEntity::getLoginTime);
        } else if ("username".equals(field)) {
            wrapper.orderBy(true, asc, AdminLoginLogEntity::getUsername);
        } else {
            throw BizException.badRequest("不支持按 " + field + " 排序");
        }
        wrapper.orderByDesc(AdminLoginLogEntity::getId);
    }

    /* ============================== 聚合 ============================== */

    /** 审计按天聚合（高危 / 其他），区间内无数据的日子补 0 */
    public List<AuditDailyRow> auditDaily(RangeQuery query) {
        LocalDateTime[] range = resolveRange(query.getFrom(), query.getTo());
        Map<String, long[]> byDay = new TreeMap<>();
        for (AdminAuditMapper.DayCountRow row : auditMapper.selectDaily(range[0], range[1])) {
            byDay.put(row.day(), new long[]{
                    row.highCount() == null ? 0L : row.highCount(),
                    row.otherCount() == null ? 0L : row.otherCount()});
        }
        List<AuditDailyRow> out = new ArrayList<>();
        for (String day : daySeries(range[0], range[1])) {
            long[] counts = byDay.getOrDefault(day, new long[]{0L, 0L});
            out.add(new AuditDailyRow(day, counts[0], counts[1]));
        }
        return out;
    }

    /** 审计按动作聚合（Top N） */
    public List<NameValueRow> auditByAction(RangeQuery query) {
        LocalDateTime[] range = resolveRange(query.getFrom(), query.getTo());
        return auditMapper.selectByAction(range[0], range[1], TOP_N).stream()
                .map(row -> new NameValueRow(row.name(), row.value() == null ? 0L : row.value()))
                .toList();
    }

    /** 登录成功 / 失败按天聚合，区间内无数据的日子补 0 */
    public List<LoginDailyRow> loginDaily(RangeQuery query) {
        LocalDateTime[] range = resolveRange(query.getFrom(), query.getTo());
        Map<String, long[]> byDay = new TreeMap<>();
        for (AdminLoginLogMapper.DayCountRow row : loginLogMapper.selectDaily(range[0], range[1])) {
            byDay.put(row.day(), new long[]{
                    row.successCount() == null ? 0L : row.successCount(),
                    row.failureCount() == null ? 0L : row.failureCount()});
        }
        List<LoginDailyRow> out = new ArrayList<>();
        for (String day : daySeries(range[0], range[1])) {
            long[] counts = byDay.getOrDefault(day, new long[]{0L, 0L});
            out.add(new LoginDailyRow(day, counts[0], counts[1]));
        }
        return out;
    }

    /** 登录失败按来源 IP 聚合（Top N） */
    public List<NameValueRow> loginFailByIp(RangeQuery query) {
        LocalDateTime[] range = resolveRange(query.getFrom(), query.getTo());
        return loginLogMapper.selectFailByIp(range[0], range[1], TOP_N).stream()
                .map(row -> new NameValueRow(row.name(), row.value() == null ? 0L : row.value()))
                .toList();
    }

    /* ============================== 导出 ============================== */

    /** 导出结果 + 实际条数 */
    public record ExportRows(List<AuditLogView> rows, boolean truncated) {
    }

    /**
     * 取导出用的审计明细（不带分页）。
     *
     * <p>有硬上限：无筛选时直接把全表读进内存会打爆服务，
     * 所以 {@code truncated} 要如实告诉调用方「被截断了」，
     * 绝不能把截断后的结果当全量交给用户（那就成了「导出看着成功、其实少了一半」）。
     */
    public ExportRows exportRows(AuditQuery query) {
        LambdaQueryWrapper<AdminAuditEntity> wrapper = Wrappers.lambdaQuery();
        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(AdminAuditEntity::getTarget, keyword)
                    .or().like(AdminAuditEntity::getOperator, keyword)
                    .or().like(AdminAuditEntity::getActionLabel, keyword)
                    .or().like(AdminAuditEntity::getAction, keyword));
        }
        applyEq(wrapper, AdminAuditEntity::getLevel, trimToNull(query.getLevel()), "level");
        applyEq(wrapper, AdminAuditEntity::getModule, trimToNull(query.getModule()), "module");
        applyEq(wrapper, AdminAuditEntity::getAction, trimToNull(query.getAction()), "action");
        applyEq(wrapper, AdminAuditEntity::getResult, trimToNull(query.getResult()), "result");
        String operator = trimToNull(query.getOperator());
        if (operator != null) {
            wrapper.like(AdminAuditEntity::getOperator, operator);
        }
        LocalDateTime from = TimeParams.parse(query.getFrom(), "from");
        LocalDateTime to = TimeParams.parse(query.getTo(), "to");
        wrapper.ge(from != null, AdminAuditEntity::getCreatedAt, from);
        wrapper.le(to != null, AdminAuditEntity::getCreatedAt, to);
        // 导出固定时间倒序，与界面默认一致
        wrapper.orderByDesc(AdminAuditEntity::getCreatedAt).orderByDesc(AdminAuditEntity::getId);

        long total = auditMapper.selectCount(wrapper);
        List<AdminAuditEntity> rows = auditMapper.selectList(wrapper.last("LIMIT " + EXPORT_LIMIT));
        return new ExportRows(rows.stream().map(AuditQueryService::toView).toList(), total > EXPORT_LIMIT);
    }

    public int exportLimit() {
        return EXPORT_LIMIT;
    }

    /* ============================== 工具 ============================== */

    /**
     * 解析聚合区间。
     *
     * <p>未传时默认「最近 14 个自然日（含今天）」——与前端 {@code last14DaysRange()} 一致。
     * 前端虽然会显式传，但接口自己也要有默认值：否则有人直接调接口就会拿到全表聚合。
     */
    private LocalDateTime[] resolveRange(String fromRaw, String toRaw) {
        LocalDateTime to = TimeParams.parse(toRaw, "to");
        LocalDateTime from = TimeParams.parse(fromRaw, "from");
        if (to == null) {
            to = LocalDateTime.now();
        }
        if (from == null) {
            from = LocalDate.now().minusDays(DEFAULT_DAYS - 1L).atStartOfDay();
        }
        if (from.isAfter(to)) {
            throw BizException.badRequest("from 不能晚于 to");
        }
        return new LocalDateTime[]{from, to};
    }

    /** 生成区间内每一天的 {@code yyyy-MM-dd} 序列（含首尾），用于补零 */
    private List<String> daySeries(LocalDateTime from, LocalDateTime to) {
        List<String> days = new ArrayList<>();
        LocalDate cursor = from.toLocalDate();
        LocalDate end = to.toLocalDate();
        while (!cursor.isAfter(end)) {
            days.add(DateTimes.DATE.format(cursor));
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    private static <T> void applyEq(LambdaQueryWrapper<AdminAuditEntity> wrapper,
                                    com.baomidou.mybatisplus.core.toolkit.support.SFunction<AdminAuditEntity, T> column,
                                    String value, String name) {
        if (StringUtils.hasText(value)) {
            wrapper.eq(column, value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AuditLogView toView(AdminAuditEntity e) {
        return new AuditLogView(
                e.getId(), e.getOperator(), e.getAction(), e.getActionLabel(), e.getModule(),
                e.getLevel(), e.getTarget(), e.getResult(), e.getMessage(), e.getIp(),
                e.getCostMs(), DateTimes.format(e.getCreatedAt()));
    }

    private static LoginLogView toView(AdminLoginLogEntity e) {
        return new LoginLogView(
                e.getId(), e.getUsername(), DateTimes.format(e.getLoginTime()),
                e.getLoginIP(), e.getLoginOS(), Boolean.TRUE.equals(e.getSuccess()), e.getInfo());
    }

    /** 聚合行的稳定输出（供测试与排查用） */
    public Map<String, Long> auditCountByLevel(RangeQuery query) {
        LocalDateTime[] range = resolveRange(query.getFrom(), query.getTo());
        Map<String, Long> out = new LinkedHashMap<>();
        for (AdminAuditMapper.DayCountRow row : auditMapper.selectDaily(range[0], range[1])) {
            out.merge("high", row.highCount() == null ? 0L : row.highCount(), Long::sum);
            out.merge("other", row.otherCount() == null ? 0L : row.otherCount(), Long::sum);
        }
        return out;
    }
}
