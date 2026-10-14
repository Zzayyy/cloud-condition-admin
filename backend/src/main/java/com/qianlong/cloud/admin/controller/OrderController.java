package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.TradeDtos.BsCountRow;
import com.qianlong.cloud.admin.dto.TradeDtos.DailyOrderRow;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderPage;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderView;
import com.qianlong.cloud.admin.dto.TradeDtos.TradePage;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeView;
import com.qianlong.cloud.admin.service.OrderService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 委托单 / 成交接口（{@code /api/trade/orders}、{@code /api/trade/trades}）。
 *
 * <p>与前端 {@code tradeApi} 对应。本模块**只读**，且数据只来自 MySQL 的
 * {@code T_Order} / {@code T_Order_his} / {@code T_Trade} / {@code T_Trade_his}。
 *
 * <p>现场实况：{@code T_Order} 0 行、{@code T_Order_his} 1 行、
 * {@code T_Trade} 0 行、{@code T_Trade_his} 1 行 —— 所以「主表」标签页是空的、
 * 「历史表」有 1 条，如实呈现，不拿别处的数据填充。
 *
 * <p><b>为什么没有写接口</b>：委托单由策略平台产生并落库，后台不修改它。
 * 前端本页也没有写操作按钮（只有列表、筛选、详情、成交下钻），
 * 所以不存在「留一个能改库但改不对的接口」的问题。
 *
 * <p>权限：只要求登录。本模块只读，未引入额外权限点 ——
 * 权限点只列真正危险的动作，避免管理员图省事全勾上而失去意义。
 */
@RestController
@RequestMapping("/trade")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /* ================================ 委托单 ================================ */

    /**
     * 委托单列表。
     *
     * <p>{@code table} 传 {@code T_Order}（主表）或 {@code T_Order_his}（历史表）——
     * 这个值直接来自前端用户可点的 tab，所以后端用 {@code TradeTable} 枚举
     * 严格白名单校验，非法值返回 40000 而不是静默回落。
     */
    @SaCheckLogin
    @GetMapping("/orders")
    public ApiResult<OrderPage> listOrders(OrderQuery query) {
        return ApiResult.ok(orderService.listOrders(query));
    }

    /** 委托单详情（先主表后历史表） */
    @SaCheckLogin
    @GetMapping("/orders/{id}")
    public ApiResult<OrderView> orderDetail(@PathVariable Long id) {
        OrderView view = orderService.getOrder(id);
        if (view == null) {
            throw OrderService.notFound(id);
        }
        return ApiResult.ok(view);
    }

    /**
     * 某委托号下的成交明细（「委托 → 成交」下钻）。
     *
     * <p>注意路径参数是**委托号**（{@code OrderNo}，业务编号），
     * 不是委托单主键 {@code id} —— 与前端 {@code listTradesByOrder(orderNo)} 一致。
     * 主表与历史表都会查（成交可能已归档），避免出现「有委托却查不到成交」。
     *
     * <p>路由注意：本方法映射 {@code /orders/{orderNo}/trades}，
     * 与 {@code /orders/{id}} 不冲突（段数不同）；而
     * {@code /orders/stats/bs} 与 {@code /orders/{id}} 段数相同，
     * 但 Spring 的路径匹配**优先精确匹配**，所以 {@code stats} 不会被当成 id 解析。
     */
    @SaCheckLogin
    @GetMapping("/orders/{orderNo}/trades")
    public ApiResult<List<TradeView>> tradesByOrder(@PathVariable String orderNo) {
        return ApiResult.ok(orderService.listTradesByOrderNo(orderNo));
    }

    /** 买卖方向分布（全量口径，前端买卖分布图用） */
    @SaCheckLogin
    @GetMapping("/orders/stats/bs")
    public ApiResult<List<BsCountRow>> bsDistribution(
            @RequestParam(required = false) String table) {
        return ApiResult.ok(orderService.bsDistribution(table));
    }

    /** 按天下单量趋势（趋势图用） */
    @SaCheckLogin
    @GetMapping("/orders/stats/daily")
    public ApiResult<List<DailyOrderRow>> dailyOrders(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResult.ok(orderService.dailyOrders(table, from, to));
    }

    /* ================================ 成交回报 ================================ */

    /**
     * 成交列表（成交回报管理页用）。
     *
     * <p>放在这个 Controller 里是为了与前端 {@code tradeApi} 的分组一致
     * （{@code listOrders} 与 {@code listTrades} 同属 {@code tradeApi}）。
     */
    @SaCheckLogin
    @GetMapping("/trades")
    public ApiResult<TradePage> listTrades(TradeQuery query) {
        return ApiResult.ok(orderService.listTrades(query));
    }
}
