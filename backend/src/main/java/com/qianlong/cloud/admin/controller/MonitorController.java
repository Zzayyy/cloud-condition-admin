package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.MonitorDtos.MetricOption;
import com.qianlong.cloud.admin.dto.MonitorDtos.TimeSeriesView;
import com.qianlong.cloud.admin.service.MonitorService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

/**
 * 指标趋势接口（{@code /api/monitor/series}）。
 *
 * <p>供「消息总线」页的趋势卡使用。**只读**，权限键 {@code menu:server-stream}。
 *
 * <h2>⚠️ 两类指标，性质完全不同（这是本接口最需要说清的事）</h2>
 * <table border="1">
 *   <tr><th>指标</th><th>历史</th><th>来源</th></tr>
 *   <tr>
 *     <td>{@code ingressRate} / {@code totalRate}</td>
 *     <td><b>真实历史</b></td>
 *     <td>Stream 条目 ID 是毫秒时间戳，保留中的条目本身就是到达记录 ——
 *         用 {@code XRANGE} 按时间窗分桶即可还原</td>
 *   </tr>
 *   <tr>
 *     <td>{@code consumerLag} / {@code consumerPending} / {@code ingressBacklog}</td>
 *     <td><b>没有</b></td>
 *     <td>{@code XINFO GROUPS} / {@code XLEN} 只有当前快照，Redis 不保存历史。
 *         接口返回 {@code historyAvailable=false} + 当前值，**不编曲线**</td>
 *   </tr>
 * </table>
 *
 * <p>「没有历史就不画」是刻意的：拿演示数据凑一条平滑曲线，会被读成
 * 「lag 在过去一小时里是这么变化的」—— 那是捏造的运维依据。
 */
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private static final String MENU_SERVER_STREAM = "menu:server-stream";

    private final MonitorService monitorService;

    /**
     * 一条趋势序列。
     *
     * @param metric    指标键，如 {@code ingressRate}；未知键**报 40000** 而不是静默回空
     * @param window    时间窗，形如 {@code 15m} / {@code 60m}（前端契约如此）；
     *                  也接受纯数字（分钟）
     */
    @SaCheckPermission(MENU_SERVER_STREAM)
    @GetMapping("/series")
    public ApiResult<TimeSeriesView> series(@RequestParam(required = false) String metric,
                                            @RequestParam(required = false) String window) {
        return ApiResult.ok(monitorService.series(metric, parseWindowMinutes(window)));
    }

    /**
     * 可用指标清单（含「有没有历史」）。
     *
     * <p>单独给出来是为了让页面**不用猜** —— 指标键写错时能立刻发现，
     * 而不是看到一个空图去怀疑后端。
     */
    @SaCheckPermission(MENU_SERVER_STREAM)
    @GetMapping("/metrics")
    public ApiResult<List<MetricOption>> metrics() {
        return ApiResult.ok(monitorService.metrics());
    }

    /** {@code "15m"} / {@code "60m"} / {@code "15"} → 分钟数；解析不出返回 null（服务层用默认值） */
    private static Integer parseWindowMinutes(String window) {
        if (window == null || window.isBlank()) {
            return null;
        }
        String s = window.trim().toLowerCase();
        if (s.endsWith("m")) {
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("h")) {
            try {
                return Integer.parseInt(s.substring(0, s.length() - 1).trim()) * 60;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
