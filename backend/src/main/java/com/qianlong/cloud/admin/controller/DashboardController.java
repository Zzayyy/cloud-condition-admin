package com.qianlong.cloud.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.DashboardDtos.DashboardOverview;
import com.qianlong.cloud.admin.service.DashboardService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 总览接口（{@code /api/dashboard/overview}）。
 *
 * <p>一次返回总览页全部内容（汇总 + 数据来源表 + 近 14 天趋势）——
 * 本页的每个数字都参与跨源对比，拆成多个接口会让前端对不齐口径与时刻。
 *
 * <h2>本接口的定位：回答「单页看不到的问题」</h2>
 * 三类服务器健康度、跨表业务量级（条件单 → 委托 → 成交）、
 * 消息总线消化情况 —— 这些分散在四个业务页里，要来回翻才能拼出来。
 *
 * <h2>⚠️ 本接口刻意「如实」而非「好看」</h2>
 * 需求方明确要求按现场实际呈现。现场当前**没有在发数据**，所以第一眼会是：
 * <ul>
 *   <li>策略服务器**全部离线** —— 因为该服务未开放（{@code nodes.strategyRegistryStale}）；</li>
 *   <li>数据中台 137 状态**未知** —— 观测不到心跳，**不等于离线**
 *       （{@code nodes.datahubUnknown} 单独计数）；</li>
 *   <li>积压上千万条，但**大部分流已停止进数据**
 *       （{@code stream.staleStreams} / {@code ingressBacklogAgeSec}）—— 那是历史堆积；</li>
 *   <li>成交率为 0% —— 因为成交未落库，不是策略失效。</li>
 * </ul>
 * 这些原因由 {@code summary.idleReason} 汇总成一句话；页面直接展示它，
 * 免得每个数字被单独误读（「在线 0 个」会被读成全线宕机）。
 *
 * <p><b>权限</b>：只要求登录。只读接口。
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 总览数据（汇总 + 数据来源与规模 + 近 14 天趋势） */
    @SaCheckLogin
    @GetMapping("/overview")
    public ApiResult<DashboardOverview> overview() {
        return ApiResult.ok(dashboardService.overview());
    }
}
