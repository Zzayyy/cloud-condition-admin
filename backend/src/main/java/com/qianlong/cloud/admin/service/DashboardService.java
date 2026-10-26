package com.qianlong.cloud.admin.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountQuery;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountRow;
import com.qianlong.cloud.admin.dto.DashboardDtos.AccountSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.ConditionalSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.DailyRow;
import com.qianlong.cloud.admin.dto.DashboardDtos.DashboardOverview;
import com.qianlong.cloud.admin.dto.DashboardDtos.DashboardSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.NodeSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.OrderSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.SourceRow;
import com.qianlong.cloud.admin.dto.DashboardDtos.StreamSummary;
import com.qianlong.cloud.admin.dto.DashboardDtos.TradeSummary;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubNodeView;
import com.qianlong.cloud.admin.dto.DatahubDtos.StreamQuery;
import com.qianlong.cloud.admin.dto.StrategyServerDtos.StrategyServerQuery;
import com.qianlong.cloud.admin.dto.TradeServerDtos.TraderServerQuery;
import com.qianlong.cloud.admin.mapper.DashboardMapper;
import com.qianlong.cloud.admin.service.DatahubHeartbeatMonitor.State;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 总览页：把分散在 MySQL 与 Redis 的横向信息聚合成一次返回。
 *
 * <h2>为什么这一页必须放后端聚合</h2>
 * 单页只能看到自己那张表；总览要的是**跨源对比**：
 * 三类服务器的健康度、跨表漏斗（条件单 → 委托 → 成交）、消息总线消化情况。
 * 前端拼这些要么调 6 个接口、要么在浏览器里对不齐口径。
 *
 * <h2>⚠️ 关键设计：汇总一律**委托给各模块自己的 service**</h2>
 * 不在这里重写「在线怎么判」「账号怎么合并」这类逻辑 ——
 * 那是各页已有的、经过现场校准的口径。若在这里重写一份，
 * 迟早会出现「总览说 1 台在线、委托服务器页说 3 台」这种无法解释的偏差。
 * 所以本类只做**编排与汇总**，判定一律复用。
 *
 * <h2>⚠️ 「如实」优先于「好看」</h2>
 * 需求方明确要求按现场实际呈现。当前现场没有在发数据，所以：
 * <ul>
 *   <li>策略服务器**未开放** → 离线是必然，用 {@code strategyRegistryStale} 说明；</li>
 *   <li>数据中台 137 **观测不到** → 保留 {@code datahubUnknown}，不并进离线；</li>
 *   <li>大部分流**已停止进数据** → 用 {@code staleStreams} 与
 *       {@code ingressBacklogAgeSec} 说清「积压是死的」；</li>
 *   <li>成交率为 0 是因为**成交没落库**，不是策略失效 → {@code idleReason} 区分。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 趋势天数（与前端「近 14 天」一致） */
    private static final int TREND_DAYS = 14;
    /** 超过这个秒数没进新数据，就认为这条流「已停止」 */
    private static final long STALE_STREAM_AFTER_SEC = 3600;

    private final DashboardMapper dashboardMapper;
    private final ConditionalService conditionalService;
    private final TradeServerService tradeServerService;
    private final StrategyServerService strategyServerService;
    private final DatahubService datahubService;
    private final DatahubHeartbeatMonitor heartbeatMonitor;
    private final AccountService accountService;

    /* ================================ 主入口 ================================ */

    public DashboardOverview overview() {
        DashboardSummary summary = buildSummary();
        return new DashboardOverview(summary, buildSources(summary), buildDaily(),
                summary.generatedAt());
    }

    /* ================================ 汇总 ================================ */

    private DashboardSummary buildSummary() {
        NodeSummary nodes = buildNodes();
        ConditionalSummary conditional = buildConditional();
        OrderSummary order = buildOrder();
        TradeSummary trade = buildTrade();
        AccountSummary account = buildAccount();
        StreamSummary stream = buildStream();

        String idleReason = describeIdle(nodes, trade, stream);
        return new DashboardSummary(nodes, conditional, order, trade, account, stream,
                idleReason != null, idleReason,
                DateTimes.format(java.time.LocalDateTime.now()));
    }

    /**
     * 节点健康度 —— 三类**分开**，且中台保留「未知」。
     *
     * <p>不复用成一个数字：三类「离线」的含义完全不同（见 DTO 注释），
     * 合并会得出「大面积宕机」的错误印象。
     */
    private NodeSummary buildNodes() {
        int tradeTotal = 0;
        int tradeOnline = 0;
        try {
            var page = tradeServerService.listServers(new TraderServerQuery());
            tradeTotal = page.meta().entryTotal();
            tradeOnline = page.meta().onlineCount();
        } catch (Exception ex) {
            log.warn("总览：取委托服务器汇总失败：{}", ex.getMessage());
        }

        int strategyTotal = 0;
        int strategyOnline = 0;
        boolean strategyStale = false;
        try {
            var page = strategyServerService.listServers(new StrategyServerQuery());
            strategyTotal = page.meta().total();
            strategyOnline = page.meta().onlineCount();
            strategyStale = page.meta().registryStale();
        } catch (Exception ex) {
            log.warn("总览：取策略服务器汇总失败：{}", ex.getMessage());
        }

        int datahubTotal = 0;
        int datahubOnline = 0;
        int datahubUnknown = 0;
        boolean heartbeatEnabled = heartbeatMonitor.isEnabled();
        try {
            // 一行 = 一台 Redis 实例（中台在 Redis 侧没有节点清单键）
            for (DatahubNodeView n : datahubService.listNodes(1, 200).list()) {
                datahubTotal++;
                if (Boolean.TRUE.equals(n.online())) {
                    datahubOnline++;
                } else if (n.online() == null) {
                    // 「观测不到」单独计数 —— 不能并进离线
                    datahubUnknown++;
                }
            }
        } catch (Exception ex) {
            log.warn("总览：取数据中台汇总失败：{}", ex.getMessage());
        }

        return new NodeSummary(tradeTotal, tradeOnline,
                strategyTotal, strategyOnline,
                datahubTotal, datahubOnline, datahubUnknown,
                strategyStale, heartbeatEnabled);
    }

    private ConditionalSummary buildConditional() {
        try {
            DashboardMapper.CountRow row = dashboardMapper.selectConditionalStats();
            if (row == null) {
                return new ConditionalSummary(0, 0, 0);
            }
            return new ConditionalSummary(row.total(), row.running(), row.triggered());
        } catch (Exception ex) {
            log.warn("总览：条件单汇总失败：{}", ex.getMessage());
            return new ConditionalSummary(0, 0, 0);
        }
    }

    private OrderSummary buildOrder() {
        try {
            return new OrderSummary(dashboardMapper.selectOrderTotal(),
                    dashboardMapper.selectOrderFilled());
        } catch (Exception ex) {
            log.warn("总览：委托汇总失败：{}", ex.getMessage());
            return new OrderSummary(0, 0);
        }
    }

    private TradeSummary buildTrade() {
        try {
            BigDecimal v = dashboardMapper.selectTradeVolume();
            return new TradeSummary(dashboardMapper.selectTradeTotal(),
                    v == null ? 0d : v.doubleValue());
        } catch (Exception ex) {
            log.warn("总览：成交汇总失败：{}", ex.getMessage());
            return new TradeSummary(0, 0);
        }
    }

    /**
     * 账号汇总。
     *
     * <p>复用 {@link AccountService#listAccounts}（MySQL + Redis 合并口径），
     * 这样「总览说 14 个在线」与账号页看到的一致。
     *
     * <p>顺带给出 {@code redisOnly} / {@code mysqlOnly} 两个差异数 ——
     * 它们是「两边数据源不一致」的直接信号，正是运维要看的。
     */
    private AccountSummary buildAccount() {
        try {
            AccountQuery q = new AccountQuery();
            q.setPage(1);
            q.setPageSize(5000); // 现场账号只有十几个；上限即可
            List<AccountRow> rows = accountService.listAccounts(q).rows();
            long total = rows.size();
            long online = rows.stream().filter(r -> r.hasRedis() && r.onlineflag() != null
                    && r.onlineflag() == 1).count();
            long redisOnly = rows.stream().filter(r -> r.hasRedis() && r.userId() == null).count();
            long mysqlOnly = rows.stream().filter(r -> !r.hasRedis() && r.userId() != null).count();
            return new AccountSummary(total, online, redisOnly, mysqlOnly);
        } catch (Exception ex) {
            log.warn("总览：账号汇总失败：{}", ex.getMessage());
            return new AccountSummary(0, 0, 0, 0);
        }
    }

    /**
     * 消息总线汇总。
     *
     * <p>复用 {@link DatahubService#listStreams}（跨实例跨 db 的同一份口径），
     * 不在这里重扫一遍 —— 否则两处算法会漂移。
     *
     * <p>⚠️ 同时给出「已停止的流」与「入口流最新条目距今」：
     * 1416 万条积压单看很吓人，但结合「入口流最后一条在 24 小时前」
     * 就知道那是历史堆积、不是当前流量。这两个数缺一不可。
     */
    private StreamSummary buildStream() {
        try {
            var streams = datahubService.listStreams(new StreamQuery());
            long count = streams.size();
            long ingress = 0;
            long lag = 0;
            long pending = 0;
            long groupLess = 0;
            long stale = 0;
            long entryTotal = 0;
            Long newestIngressAge = null;

            for (var s : streams) {
                entryTotal += s.length();
                // ⚠️ 必须同时判 length > 0：空流没有消费组是**正常**的
                //    （没人写也没人读），只判 groups.isEmpty() 会把它当风险，
                //    进而在总览页说「有人在往里面写、消息只进不出」——那是假话。
                if (s.groups().isEmpty() && s.length() > 0) {
                    groupLess++;
                }
                for (var g : s.groups()) {
                    lag += g.lag() == null ? 0 : g.lag();
                    pending += g.pending();
                }
                if (s.lastEntryAgeSec() != null && s.lastEntryAgeSec() > STALE_STREAM_AFTER_SEC) {
                    stale++;
                }
                if ("DataHub_req_stream".equals(s.key())) {
                    ingress += s.length();
                    // 取入口流里**最新**的那条（age 最小的），代表「这个方向上最后的活动」
                    if (s.lastEntryAgeSec() != null
                            && (newestIngressAge == null || s.lastEntryAgeSec() < newestIngressAge)) {
                        newestIngressAge = s.lastEntryAgeSec();
                    }
                }
            }
            return new StreamSummary(count, ingress, lag, pending, groupLess, stale,
                    newestIngressAge, entryTotal);
        } catch (Exception ex) {
            log.warn("总览：消息总线汇总失败：{}", ex.getMessage());
            return new StreamSummary(0, 0, 0, 0, 0, 0, null, 0);
        }
    }

    /**
     * 「现场当前没有在发数据」的判定与说明。
     *
     * <p>把这些信号拼成一句话，是因为**单个数字都会被误读**：
     * <ul>
     *   <li>「在线 0 个」→ 像全线宕机，其实策略服务本来就没开放；</li>
     *   <li>「成交率 0%」→ 像策略失效，其实是成交没落库；</li>
     *   <li>「积压 1416 万」→ 像流量很大，其实是历史堆积。</li>
     * </ul>
     * 放在一起说，才不会被各自误读。
     *
     * @return 需要提醒时的说明文字；一切正常时返回 {@code null}
     */
    private String describeIdle(NodeSummary nodes, TradeSummary trade, StreamSummary stream) {
        List<String> parts = new ArrayList<>();

        if (nodes.strategyTotal() > 0 && nodes.strategyRegistryStale()) {
            parts.add("策略服务器未开放（该注册表已长时间未刷新），其节点恒为离线，不必按故障排查");
        }
        if (nodes.datahubUnknown() > 0) {
            parts.add("数据中台有 " + nodes.datahubUnknown() + " 个实例状态为「未知」（观测不到心跳，不是离线）");
        }
        if (stream.staleStreams() > 0) {
            String age = stream.ingressBacklogAgeSec() == null
                    ? "无可用条目"
                    : humanAge(stream.ingressBacklogAgeSec());
            parts.add("有 " + stream.staleStreams() + "/" + stream.count()
                    + " 条流已超过 1 小时没有新条目（入口流最后一条在 " + age + " 前）——"
                    + "积压是历史堆积，不代表当前流量");
        }
        if (trade.total() == 0) {
            parts.add("成交表当前为空：若委托已有成交状态但这里为 0，说明成交尚未落库");
        }

        return parts.isEmpty() ? null : String.join("；", parts) + "。";
    }

    /** 把秒数说成人话（页面提示用） */
    private static String humanAge(long sec) {
        if (sec >= 86400) {
            return String.format("%.1f 天", sec / 86400.0);
        }
        if (sec >= 3600) {
            return String.format("%.1f 小时", sec / 3600.0);
        }
        return sec + " 秒";
    }

    /* ============================ 数据来源与规模 ============================ */

    /**
     * 「数据来源与规模」表。
     *
     * <p>这张表的价值在于**说清每个数字来自哪里、口径是什么** ——
     * 总览页最容易出的问题就是「数字看着对、其实口径不对」。
     * 所以每行都带 {@code hint}。
     */
    private List<SourceRow> buildSources(DashboardSummary s) {
        List<SourceRow> rows = new ArrayList<>();
        rows.add(new SourceRow("DataHub_req_stream", "redis", "Redis Stream",
                compact(s.stream().ingressBacklog()),
                "入口流积压（各实例 XLEN 合计）；有 " + s.stream().groupLess() + " 条流没有消费组"));
        rows.add(new SourceRow("全部 Redis Stream", "redis", "Redis Stream",
                s.stream().count() + " 条 / " + compact(s.stream().entryTotal()),
                "跨 136/137 全部 db；其中 " + s.stream().staleStreams() + " 条已超过 1 小时无新数据"));
        rows.add(new SourceRow("traderserver", "redis", "Redis JSON",
                s.nodes().tradeTotal() + " 条记录",
                "委托服务器节点清单（跨实例全读），在线 " + s.nodes().tradeOnline()));
        rows.add(new SourceRow("strategy-srv", "redis", "Redis JSON",
                s.nodes().strategyTotal() + " 条记录",
                s.nodes().strategyRegistryStale()
                        ? "策略服务器节点清单；注册表未刷新（该服务未开放）"
                        : "策略服务器节点清单，含 usecount（承载条件单用户数）"));
        rows.add(new SourceRow("acc-*", "redis", "Redis JSON",
                s.account().online() + " 个在线 / " + s.account().total() + " 个账号",
                "账号实时态对象；Redis 独有 " + s.account().redisOnly()
                        + " 个，MySQL 独有 " + s.account().mysqlOnly() + " 个"));
        rows.add(new SourceRow("V_Conditional", "mysql", "MySQL",
                s.conditional().total() + " 条",
                "主表 + 历史表的并集视图；运行中 " + s.conditional().running()
                        + " 条，已触发 " + s.conditional().triggered() + " 条"));
        rows.add(new SourceRow("T_Order(_his)", "mysql", "MySQL",
                s.order().total() + " 条",
                "委托单；其中 " + s.order().filled() + " 条已按 OrderNo 关联到成交"));
        rows.add(new SourceRow("T_Trade(_his)", "mysql", "MySQL",
                s.trade().total() + " 条",
                "成交明细；成交量合计 " + s.trade().volume()
                        + (s.trade().total() == 0 ? "（表为空：现场成交未落库）" : "")));
        rows.add(new SourceRow("T_StrategyLog", "mysql", "MySQL", "1.77 万行（现场快照）",
                "策略单日志；Status/FromIP/FromMac 三列现场全为 NULL"));
        rows.add(new SourceRow("T_UserLog", "mysql", "MySQL", "19.11 万行（现场快照）",
                "登录日志，查询必须分页；LogIP/LogMAC/LogOS 三列全为 NULL"));
        return rows;
    }

    /** 规模的紧凑写法（与前端演示态一致），便于阅读 */
    private static String compact(long n) {
        if (n >= 100_000_000L) {
            return String.format("%.2f 亿条", n / 100_000_000.0);
        }
        if (n >= 10_000L) {
            return String.format("%.1f 万条", n / 10_000.0);
        }
        return n + " 条";
    }

    /* ================================ 趋势 ================================ */

    /**
     * 近 14 天：条件单新增 / 委托 / 成交三条线。
     *
     * <p>三条线**跨三个数据源**（V_Conditional、T_Order(_his)、T_Trade(_his)），
     * 任何单独一页都看不到 —— 这正是总览页存在的意义。
     *
     * <p>⚠️ 三条线的口径宽度不同（条件单含历史表并集；委托与成交各自主+历史），
     * 已在 SQL 注释里写明。
     *
     * <p>**按天补零**：没有数据的日子也要有点，否则折线会把两个相隔多日的点
     * 直接连起来，看起来像「一直有数据」。
     */
    private List<DailyRow> buildDaily() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(TREND_DAYS - 1L);
        String f = DateTimes.DATE.format(from);
        String t = DateTimes.DATE.format(to);

        Map<String, Long> cond = toMap(dashboardMapper.selectConditionalDaily(f, t));
        Map<String, Long> ord = toMap(dashboardMapper.selectOrderDaily(f, t));
        Map<String, Long> trd = toMap(dashboardMapper.selectTradeDaily(f, t));

        List<DailyRow> out = new ArrayList<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            String day = DateTimes.DATE.format(from.plusDays(i));
            out.add(new DailyRow(day,
                    cond.getOrDefault(day, 0L),
                    ord.getOrDefault(day, 0L),
                    trd.getOrDefault(day, 0L)));
        }
        return out;
    }

    private static Map<String, Long> toMap(List<DashboardMapper.DayCount> rows) {
        if (rows == null) {
            return Map.of();
        }
        return rows.stream().collect(Collectors.toMap(
                DashboardMapper.DayCount::day,
                DashboardMapper.DayCount::cnt,
                Long::sum,
                LinkedHashMap::new));
    }
}
