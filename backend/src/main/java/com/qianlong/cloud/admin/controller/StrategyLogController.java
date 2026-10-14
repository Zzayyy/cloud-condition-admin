package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.DailyLogRow;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogPage;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogQuery;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyLogView;
import com.qianlong.cloud.admin.dto.StrategyLogDtos.StrategyStatRow;
import com.qianlong.cloud.admin.service.StrategyLogService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 策略日志接口（{@code /api/strategy/logs}）。
 *
 * <p>与前端 {@code strategyApi.listLogs} 对应。只读 {@code T_StrategyLog}
 * （现场 17430 行），不掺任何其它数据源。
 *
 * <h2>⚠️ 三条现场事实（接口如实返回，不粉饰）</h2>
 * <ol>
 *   <li><b>{@code Status} 整列为 NULL。</b>所以前端那个状态下拉
 *       （触发成功 / 触发失败 / …）是 {@code mock/strategy.ts} 编的，
 *       现实里一个都不存在。本控制器提供
 *       {@code GET /strategy/logs/statuses} 返回**真实**去重取值（空数组），
 *       前端应改用它而不是从 mock 取。
 *       {@code meta.statusColumnEmpty} 也一并返回，便于页面直接说明。</li>
 *   <li><b>{@code FromIP} / {@code FromMac} 整列为 NULL。</b>
 *       「按来源 IP 筛」必然查出 0 条 —— 不是查询写错了。</li>
 *   <li>{@code OccurTIme} 是 {@code date}（无时分秒），
 *       且该列存过非法空串，SQL 里**不能**用 {@code <> ''} 比较
 *       （会报 MySQL 1525 让整条查询失败）。</li>
 * </ol>
 *
 * <p><b>为什么没有写接口</b>：日志由策略平台追加写入，后台只读 ——
 * 没有任何「修改一条日志」的合理语义。
 *
 * <p>权限：只要求登录。只读模块不引入额外权限点。
 */
@RestController
@RequestMapping("/strategy/logs")
@RequiredArgsConstructor
public class StrategyLogController {

    private final StrategyLogService strategyLogService;

    /**
     * 策略日志列表。
     *
     * <p>筛选：{@code keyword}（策略编号 / 日志内容 / 来源账号，三处模糊匹配）、
     * {@code status}、{@code fromIp}、{@code from}/{@code to} 时间范围。
     */
    @SaCheckLogin
    @GetMapping
    public ApiResult<StrategyLogPage> list(StrategyLogQuery query) {
        return ApiResult.ok(strategyLogService.listLogs(query));
    }

    /**
     * {@code Status} 列的**真实**去重取值（当前为空数组）。
     *
     * <p>前端应当用它渲染状态下拉，而不是从 {@code mock/strategy.ts} 的
     * {@code LOG_STATUS} 取 —— 那 7 个值现场一个都不存在，
     * 做成下拉会让人「选一个、查出 0 条、以为查询坏了」。
     */
    @SaCheckLogin
    @GetMapping("/statuses")
    public ApiResult<List<String>> statuses() {
        return ApiResult.ok(strategyLogService.statuses());
    }

    /** 按天日志量（趋势图） */
    @SaCheckLogin
    @GetMapping("/stats/daily")
    public ApiResult<List<DailyLogRow>> daily(@RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return ApiResult.ok(strategyLogService.dailyLogs(from, to));
    }

    /** 按条件单聚合（「按策略单查看生命周期」） */
    @SaCheckLogin
    @GetMapping("/stats/by-strategy")
    public ApiResult<PageResult<StrategyStatRow>> byStrategy(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResult.ok(strategyLogService.strategyStats(
                keyword, page == null ? 1 : page, pageSize == null ? 20 : pageSize));
    }

    /**
     * 某个条件单的完整日志轨迹（时间线，按时间升序）。
     *
     * <p>与列表的「最新在前」方向相反 —— 轨迹要按时间顺序看才有意义。
     */
    @SaCheckLogin
    @GetMapping("/{strategy}/timeline")
    public ApiResult<List<StrategyLogView>> timeline(@PathVariable Long strategy) {
        return ApiResult.ok(strategyLogService.timeline(strategy));
    }
}
