package com.qianlong.cloud.admin.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.TradeDtos.BsCountRow;
import com.qianlong.cloud.admin.dto.TradeDtos.CodeCountRow;
import com.qianlong.cloud.admin.dto.TradeDtos.DailyOrderRow;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderPage;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderStats;
import com.qianlong.cloud.admin.dto.TradeDtos.OrderView;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeDailyRow;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeListMeta;
import com.qianlong.cloud.admin.dto.TradeDtos.TradePage;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeQuery;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeStats;
import com.qianlong.cloud.admin.dto.TradeDtos.TradeView;
import com.qianlong.cloud.admin.entity.OrderEntity;
import com.qianlong.cloud.admin.entity.TradeEntity;
import com.qianlong.cloud.admin.mapper.OrderMapper;
import com.qianlong.cloud.admin.mapper.TradeMapper;

import lombok.RequiredArgsConstructor;

/**
 * 委托单查询 —— 只读 {@code T_Order} / {@code T_Order_his}。
 *
 * <p><b>不加任何其它数据源</b>：委托单由策略平台产生并落库，这三张表
 * （含成交表）就是权威来源。条件单那边曾经把 Redis 的数据混进来，
 * 结果让页面读数失去意义（详见 {@code ConditionalService} 的说明），
 * 这里不再重复那个错误。
 *
 * <h2>现场实况（2026-10-14 实测）</h2>
 * <pre>
 *   T_Order        0 行
 *   T_Order_his    1 行（测试数据，OrderNo=1234567）
 *   T_Trade        0 行
 *   T_Trade_his    1 行（同一笔 OrderNo=1234567）
 * </pre>
 * 所以「历史表」标签页能看到 1 条，「主表」是空的 —— 如实呈现。
 *
 * <h2>⚠️ 两个列类型带来的口径问题</h2>
 * <ol>
 *   <li>{@code OrderTime} / {@code TradeTime} 是 <b>{@code date}</b>
 *       （不是 datetime），所以**没有时分秒**。接口返回 {@code yyyy-MM-dd}，
 *       不补 {@code 00:00:00} —— 补零会让人误以为真有那个精确时刻。</li>
 *   <li>{@code TradeEntity.Volumn} 是 <b>{@code varchar}</b>，按字符串返回
 *       （前端契约也是 {@code string | null}），不擅自转数字。</li>
 * </ol>
 *
 * <h2>⚠️ {@code OrderStatue} 的含义未知</h2>
 * 协议文档没定义取值，现场 {@code T_Order_his} 只有 1 条测试数据（值为 1）。
 * 前端只展示 {@code StatueDis} 文本与原值、不做状态着色；
 * 后端同样**不臆造映射** —— 这是 README 里记着的待确认项。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final TradeMapper tradeMapper;

    /* ================================ 委托单 ================================ */

    /** 分页查委托单（主表/历史表由 {@code table} 决定） */
    public OrderPage listOrders(OrderQuery query) {
        TradeTable table = TradeTable.ofOrder(query.getTable());
        String keyword = trimToNull(query.getKeyword());
        String code = trimToNull(query.getCode());

        long total = orderMapper.countPage(
                table.table(), keyword, query.getBsType(), query.getOcType(), code);

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long offset = (long) (page - 1) * pageSize;

        List<OrderView> rows = orderMapper.selectPage(
                        table.table(), keyword, query.getBsType(), query.getOcType(), code,
                        offset, pageSize)
                .stream().map(OrderService::toView).toList();

        return new OrderPage(rows, total, page, pageSize,
                new TradeListMeta(table.table(), table.label(), total));
    }

    /**
     * 单条委托详情。
     *
     * <p>依次在主表、历史表里找 —— 详情接口不带 {@code table}，
     * 前端从列表点进来时虽然知道自己当前在哪个 tab，但成交下钻等场景
     * 可能拿到一个不在当前 tab 的 id，按表逐个找更稳。
     */
    public OrderView getOrder(Long id) {
        if (id == null) {
            return null;
        }
        for (TradeTable table : List.of(TradeTable.ORDER, TradeTable.ORDER_HIS)) {
            OrderEntity hit = orderMapper.selectByIdIn(table.table(), id);
            if (hit != null) {
                return toView(hit);
            }
        }
        return null;
    }

    /** 买卖方向分布（全量口径；由后端 GROUP BY 算，前端不做统计） */
    public List<BsCountRow> bsDistribution(String tableName) {
        TradeTable table = TradeTable.ofOrder(tableName);
        List<BsCountRow> out = new ArrayList<>();
        for (OrderMapper.BsCountRow row : orderMapper.countByBsType(table.table())) {
            out.add(new BsCountRow(
                    row.bsType(),
                    bsLabel(row.bsType()),
                    row.cnt() == null ? 0L : row.cnt()));
        }
        return out;
    }

    /** 按天下单量趋势 */
    public List<DailyOrderRow> dailyOrders(String tableName, String from, String to) {
        TradeTable table = TradeTable.ofOrder(tableName);
        LocalDate f = parseDate(from, "from");
        LocalDate t = parseDate(to, "to");
        List<DailyOrderRow> out = new ArrayList<>();
        for (OrderMapper.DayCountRow row : orderMapper.selectDaily(table.table(), f, t)) {
            out.add(new DailyOrderRow(row.day(), row.cnt() == null ? 0L : row.cnt()));
        }
        return out;
    }

    /**
     * 委托单汇总（全量口径，卡片用）。
     *
     * <p>按当前筛选条件统计全表 —— 卡片标签写的是「买入委托」这种像全量的说法，
     * 给当前页的数字会被读成总量，而且翻页就变。
     *
     * <p>筛选条件与 {@code listOrders} 完全一致，所以 {@code totalCount}
     * 应与列表 {@code total} 相等；不相等说明两处写岔了。
     */
    public OrderStats stats(OrderQuery query) {
        TradeTable table = TradeTable.ofOrder(query.getTable());
        OrderMapper.OrderStatsRow row = orderMapper.selectStats(
                table.table(), trimToNull(query.getKeyword()),
                query.getBsType(), query.getOcType(), trimToNull(query.getCode()));
        if (row == null) {
            return new OrderStats(0, 0, 0, 0, 0);
        }
        return new OrderStats(
                nz(row.buyCount()), nz(row.sellCount()), nz(row.abnormalCount()),
                row.totalAmount() == null ? 0d : row.totalAmount(),
                nz(row.totalCount()));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /* ================================ 成交 ================================ */

    /**
     * 成交汇总（全量口径，卡片用）。
     *
     * <p>成交金额 / 总量由数据库把 varchar 的 {@code Volumn} 转 DECIMAL 后求和，
     * 均价用「总额 ÷ 总量」算 —— 这些都不该由前端拿当前页自己加。
     */
    public TradeStats tradeStats(TradeQuery query) {
        TradeTable table = TradeTable.ofTrade(query.getTable());
        String keyword = trimToNull(query.getKeyword());
        String code = trimToNull(query.getCode());

        TradeMapper.TradeStatsRow row = tradeMapper.selectStats(table.table(), keyword, code);
        if (row == null) {
            return new TradeStats(0, 0, 0, 0);
        }
        double volumn = row.totalVolumn() == null ? 0d : row.totalVolumn().doubleValue();
        double amount = row.totalAmount() == null ? 0d : row.totalAmount().doubleValue();
        double avg = volumn > 0 ? amount / volumn : 0d;
        return new TradeStats(nz(row.totalCount()), volumn, amount, avg);
    }

    /**
     * 按合约的成交量分布（全量口径，合约分布图用）。
     *
     * <p>同样不能在 前端按当前页分组 —— 那只是这一页的分布。
     */
    public List<CodeCountRow> codeDistribution(TradeQuery query, int limit) {
        TradeTable table = TradeTable.ofTrade(query.getTable());
        int safeLimit = Math.min(Math.max(1, limit), 50);
        return tradeMapper.countByCode(
                        table.table(), trimToNull(query.getKeyword()),
                        trimToNull(query.getCode()), safeLimit).stream()
                .map(r -> new CodeCountRow(
                        r.code() == null ? "未知" : r.code(),
                        r.cnt() == null ? 0d : r.cnt().doubleValue()))
                .toList();
    }

    /**
     * 按天成交笔数与金额（趋势图，**全量口径**）。
     *
     * <p>补的是这个页面**唯一缺失的维度：时间**。
     * 饼图给「成交集中在哪个合约」、卡片给「总量多少」，
     * 但「500 万是某一天一笔打出来的」与「均匀分布在 14 天」是两件事 ——
     * 不画时序就分不出来。
     *
     * <p>⚠️ 现场 {@code T_Trade} 0 行、{@code T_Trade_his} 1 行，
     * 所以大概率只返回一个很小的时间点或空列表 —— 如实呈现，不拿假数据填。
     */
    public List<TradeDailyRow> tradeDaily(String tableName, String keyword, String code) {
        TradeTable table = TradeTable.ofTrade(tableName);
        List<TradeDailyRow> out = new ArrayList<>();
        for (TradeMapper.TradeDailyRow row : tradeMapper.selectDaily(
                table.table(), trimToNull(keyword), trimToNull(code))) {
            out.add(new TradeDailyRow(
                    row.day(),
                    row.tradeCount() == null ? 0L : row.tradeCount(),
                    row.amount() == null ? 0d : row.amount().doubleValue()));
        }
        return out;
    }

    /* ================================ 成交 ================================ */

    /** 分页查成交（成交回报页用） */
    public TradePage listTrades(TradeQuery query) {
        TradeTable table = TradeTable.ofTrade(query.getTable());
        String keyword = trimToNull(query.getKeyword());
        String code = trimToNull(query.getCode());

        long total = tradeMapper.countPage(table.table(), keyword, code);

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long offset = (long) (page - 1) * pageSize;

        List<TradeView> rows = tradeMapper.selectPage(
                        table.table(), keyword, code, offset, pageSize)
                .stream().map(OrderService::toView).toList();

        return new TradePage(rows, total, page, pageSize,
                new TradeListMeta(table.table(), table.label(), total));
    }

    /**
     * 某委托号下的全部成交（「委托 → 成交」下钻）。
     *
     * <p><b>两张成交表都查</b>：委托可能还在主表、成交已归档到历史表（或反之），
     * 只查一张会显示「没有成交」—— 那与「确实没成交」在页面上长得一样。
     */
    public List<TradeView> listTradesByOrderNo(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return List.of();
        }
        return tradeMapper.selectByOrderNo(orderNo.trim()).stream()
                .map(OrderService::toView)
                .toList();
    }

    /* ================================ 映射 ================================ */

    private static OrderView toView(OrderEntity e) {
        return new OrderView(
                e.getID(),
                e.getStrategyID(),
                e.getUserAccount(),
                e.getAccount(),
                e.getCode(),
                e.getMarket(),
                e.getOrderPrice(),
                e.getOrderVolumn(),
                e.getBSType(),
                e.getOCType(),
                // date 列：只给日期，不补 00:00:00
                DateTimes.formatLocalDate(e.getOrderTime()),
                e.getOrderParam(),
                e.getOrderStatue(),
                e.getOrderNo(),
                e.getCodeName(),
                e.getStatueDis(),
                e.getReason());
    }

    private static TradeView toView(TradeEntity e) {
        return new TradeView(
                e.getId(),
                e.getStrategyID(),
                e.getOrderNo(),
                e.getTradeNO(),
                e.getAccount(),
                e.getMarket(),
                e.getCode(),
                // varchar 列：按字符串返回，不转数字
                e.getVolumn(),
                e.getPrice(),
                DateTimes.formatLocalDate(e.getTradeTime()),
                e.getUserAccount());
    }

    /**
     * 买卖方向标签。
     *
     * <p>与前端 {@code BS_TYPES} 保持一致（1 买 / 2 卖）。
     * 不在列表里的值原样显示 {@code 方向 N}，不猜。
     */
    private static String bsLabel(Integer bsType) {
        if (bsType == null) {
            return "未知";
        }
        return switch (bsType) {
            case 1 -> "买入";
            case 2 -> "卖出";
            default -> "方向 " + bsType;
        };
    }

    /**
     * 解析日期范围。
     *
     * <p>{@code OrderTime} 是 {@code date} 类型，所以这里也按日期解析。
     * 若前端传了带时分秒的字符串，{@link TimeParams#parse} 会先解析成
     * datetime 再取日期部分，不至于因为「多了个时间」就报错。
     */
    private static LocalDate parseDate(String raw, String name) {
        var dt = TimeParams.parse(raw, name);
        return dt == null ? null : dt.toLocalDate();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 委托单不存在时抛出（供 controller 用，保持与其它模块一致的错误码） */
    public static BizException notFound(Long id) {
        return BizException.notFound("委托单不存在：" + id);
    }
}
