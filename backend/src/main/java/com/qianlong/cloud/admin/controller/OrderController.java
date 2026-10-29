package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.AccountDtos.RedisAccountView;
import com.qianlong.cloud.admin.dto.TradeDtos.BsCountRow;
import com.qianlong.cloud.admin.dto.TradeDtos.CodeCountRow;
import com.qianlong.cloud.admin.dto.TradeDtos.DailyOrderRow;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderPage;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderStats;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderView;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeDailyRow;
import com.qianlong.cloud.admin.dto.TradeDtos.TradePage;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeStats;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeView;
import com.qianlong.cloud.admin.dto.TradeServerDtos.AssignmentQuery;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TradeServerPage;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TraderServerQuery;
import com.qianlong.cloud.admin.service.OrderService;
import com.qianlong.cloud.admin.service.TradeServerService;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
 * <p>权限：按页面校验菜单权限（{@code /trade/orders} → {@code menu:order}、
 * {@code /trade/trades} → {@code menu:deal}、{@code /trade/servers|assignments}
 * → {@code menu:server-trade}）。本模块只读，没有操作权限点 ——
 * 操作权限点只列真正危险的动作，避免管理员图省事全勾上而失去意义。
 */
@RestController
@RequestMapping("/trade")
@RequiredArgsConstructor
public class OrderController {

    /** 菜单权限：委托单管理页 */
    private static final String MENU_ORDER = "menu:order";

    /** 菜单权限：成交回报管理页 */
    private static final String MENU_DEAL = "menu:deal";

    /** 菜单权限：委托服务器页（节点与账号分配关系） */
    private static final String MENU_SERVER_TRADE = "menu:server-trade";

    private final OrderService orderService;
    private final TradeServerService tradeServerService;

    /* ================================ 委托单 ================================ */

    /**
     * 委托单列表。
     *
     * <p>{@code table} 传 {@code T_Order}（主表）或 {@code T_Order_his}（历史表）——
     * 这个值直接来自前端用户可点的 tab，所以后端用 {@code TradeTable} 枚举
     * 严格白名单校验，非法值返回 40000 而不是静默回落。
     */
    @SaCheckPermission(MENU_ORDER)
    @GetMapping("/orders")
    public ApiResult<OrderPage> listOrders(OrderQuery query) {
        return ApiResult.ok(orderService.listOrders(query));
    }

    /** 委托单详情（先主表后历史表） */
    @SaCheckPermission(MENU_ORDER)
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
    @SaCheckPermission(MENU_ORDER)
    @GetMapping("/orders/{orderNo}/trades")
    public ApiResult<List<TradeView>> tradesByOrder(@PathVariable String orderNo) {
        return ApiResult.ok(orderService.listTradesByOrderNo(orderNo));
    }

    /** 买卖方向分布（全量口径，前端买卖分布图用） */
    @SaCheckPermission(MENU_ORDER)
    @GetMapping("/orders/stats/bs")
    public ApiResult<List<BsCountRow>> bsDistribution(
            @RequestParam(required = false) String table) {
        return ApiResult.ok(orderService.bsDistribution(table));
    }

    /** 按天下单量趋势（趋势图用） */
    @SaCheckPermission(MENU_ORDER)
    @GetMapping("/orders/stats/daily")
    public ApiResult<List<DailyOrderRow>> dailyOrders(
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResult.ok(orderService.dailyOrders(table, from, to));
    }

    /**
     * 委托单汇总（**全量口径**，卡片用）。
     *
     * <p>参数与 {@code /orders} 列表一致（keyword / bsType / ocType / code / table），
     * 所以 {@code totalCount} 应与列表的 {@code total} 相等 ——
     * 卡片数字与「共 N 条」对不上时可据此立刻定位。
     */
    @SaCheckPermission(MENU_ORDER)
    @GetMapping("/orders/stats/summary")
    public ApiResult<OrderStats> orderStats(OrderQuery query) {
        return ApiResult.ok(orderService.stats(query));
    }

    /* ================================ 成交回报 ================================ */

    /**
     * 成交列表（成交回报管理页用）。
     *
     * <p>放在这个 Controller 里是为了与前端 {@code tradeApi} 的分组一致
     * （{@code listOrders} 与 {@code listTrades} 同属 {@code tradeApi}）。
     */
    @SaCheckPermission(MENU_DEAL)
    @GetMapping("/trades")
    public ApiResult<TradePage> listTrades(TradeQuery query) {
        return ApiResult.ok(orderService.listTrades(query));
    }

    /**
     * 成交汇总（**全量口径**，卡片用）。
     *
     * <p>成交金额 / 总量 / 均价都由数据库算准，前端不再按当前页自己加。
     * 均价是「总额 ÷ 总量」，不是对每页均价的平均。
     */
    @SaCheckPermission(MENU_DEAL)
    @GetMapping("/trades/stats/summary")
    public ApiResult<TradeStats> tradeStats(TradeQuery query) {
        return ApiResult.ok(orderService.tradeStats(query));
    }

    /**
     * 按天成交笔数与金额（趋势图，**全量口径**）。
     *
     * <p>参数与 {@code /trades} 列表一致（keyword / code / table），
     * 切换「当前成交 / 历史成交」tab 时趋势跟着变。
     */
    @SaCheckPermission(MENU_DEAL)
    @GetMapping("/trades/stats/daily")
    public ApiResult<List<TradeDailyRow>> tradeDaily(TradeQuery query) {
        return ApiResult.ok(orderService.tradeDaily(
                query.getTable(), query.getKeyword(), query.getCode()));
    }

    /**
     * 按合约的成交量分布（**全量口径**，合约分布图用）。
     *
     * <p>{@code limit} 默认 8（与页面上「最多显示 8 个合约」一致）。
     */
    @SaCheckPermission(MENU_DEAL)
    @GetMapping("/trades/stats/codes")
    public ApiResult<List<CodeCountRow>> tradeCodes(
            TradeQuery query,
            @RequestParam(required = false, defaultValue = "8") Integer limit) {
        return ApiResult.ok(orderService.codeDistribution(query, limit));
    }

    /* ============================== 委托服务器管理 ============================== */

    /**
     * 委托服务器节点列表（服务器管理 / 委托服务器页）。
     *
     * <p>数据来自 Redis 键 {@code traderserver}，且**跨实例全读**：
     * 现场同一个键在 136 db0、136 db1、137 db1 上各存一份且内容不同
     * （只有 136 db1 里那条 {@code id=1} 是活的）。按 id 去重会把
     * 「4 条 {@code id=0}」这种现场真实存在的重复记录藏起来，
     * 所以这里如实全部列出，靠 {@code instance} + {@code arrayIndex} 区分行。
     *
     * <p>默认排序是**在线优先 + 心跳倒序**：不这么排的话，进页面第一眼
     * 全是几个月前的残留，唯一活着的那台被挤到后面。
     */
    @SaCheckPermission(MENU_SERVER_TRADE)
    @GetMapping("/servers")
    public ApiResult<TradeServerPage> listServers(TraderServerQuery query) {
        return ApiResult.ok(tradeServerService.listServers(query));
    }

    /**
     * 账号 → 委托服务器的分配关系。
     *
     * <p>数据源是 {@code acc-*} 键里的 {@code traderserver} 字段，
     * 复用账号页的同一份映射（返回类型就是前端 {@code RedisAccount}）。
     */
    @SaCheckPermission(MENU_SERVER_TRADE)
    @GetMapping("/assignments")
    public ApiResult<PageResult<RedisAccountView>> listAssignments(AssignmentQuery query) {
        TradeServerService.MergedAssignments result = tradeServerService.listAssignments(query);
        return ApiResult.ok(PageResult.of(
                result.rows(), result.total(),
                query.pageOrDefault(), query.pageSizeOrDefault()));
    }

    /**
     * 某台委托服务器承载的账号（节点详情抽屉的下钻）。
     *
     * <p>⚠️ 按 {@code id} 匹配，而现场 {@code id} **会重复**
     * （136 db1 的 7 条里有 4 个 {@code id=0}），所以那几个同 id 的节点
     * 会显示承载同一批账号。这是数据本身如此，不是查询写错；
     * 页面已注明「按 id 匹配，因 id 会重复仅供参考」。
     */
    @SaCheckPermission(MENU_SERVER_TRADE)
    @GetMapping("/servers/{serverId}/accounts")
    public ApiResult<List<RedisAccountView>> accountsByServer(@PathVariable Integer serverId) {
        return ApiResult.ok(tradeServerService.listAccountsByServer(serverId));
    }
}
