package com.qianlong.cloud.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerPage;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerQuery;
import com.qianlong.cloud.admin.service.StrategyServerService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

/**
 * 策略服务器接口（{@code /api/strategy/servers}）。
 *
 * <p>与前端 {@code strategyApi.listServers} 对应。数据只来自 Redis 键
 * {@code strategy-srv}，**跨实例全读**（现场 136 db0 / 136 db1 各有一份、内容不同，
 * 137 db1 没有这个键）。
 *
 * <h2>⚠️ 三条如实说明（都写进了响应里，不只是文档）</h2>
 * <ol>
 *   <li><b>策略服务器当前未开放。</b>注册表不刷新（盯 30 秒内容不变）、
 *       心跳频道上也收不到心跳 —— 后者是<b>预期现象</b>，所以本模块
 *       <b>不加常驻订阅</b>（加了也收不到，还会因 Redis 的
 *       {@code PUBSUB CHANNELS} 只列有订阅者的频道而凭空造出订阅者，
 *       污染 Redis 管理页）。meta 里的 {@code registryStale}
 *       与 {@code note} 会把这件事讲清楚。</li>
 *   <li><b>{@code usecount} 现场全部为 0</b>（该字段未上报）——
 *       不代表「策略平台没有承载条件单用户」，页面已标注。</li>
 *   <li><b>没有启停接口。</b>现场 {@code id} 全是 0、{@code mac} 又各带实例后缀，
 *       没有任何字段能稳定指回「同一台机器」，按它们写回容易改错机器。
 *       与委托服务器同理，只呈现状态。相应地前端那个 {@code NSwitch}
 *       也改为只读标签。</li>
 * </ol>
 *
 * <p><b>权限</b>：{@code menu:server-strategy}。本模块**只读**，未引入操作权限点。
 */
@RestController
@RequestMapping("/strategy/servers")
@RequiredArgsConstructor
public class StrategyServerController {

    private static final String MENU_SERVER_STRATEGY = "menu:server-strategy";

    private final StrategyServerService strategyServerService;

    /**
     * 策略服务器节点列表。
     *
     * <p>汇总口径说明：{@code meta} 里的在线数 / usecount 合计与最大值
     * 都是**全量**算的，不是当前页 —— 前端原先按当前页 filter，
     * 翻页时「在线节点」会莫名变化。
     */
    @SaCheckPermission(MENU_SERVER_STRATEGY)
    @GetMapping
    public ApiResult<StrategyServerPage> list(StrategyServerQuery query) {
        return ApiResult.ok(strategyServerService.listServers(query));
    }
}
