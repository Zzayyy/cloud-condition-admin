/**
 * 总览页 —— 演示数据（Mock）。
 *
 * ⚠️ **本文件不另造一套数字**：所有汇总都从各业务模块已有的 mock 数据
 * **现场派生**（条件单、委托、成交、账号、节点、流）。
 *
 * 为什么坚持派生而不是手写一份汇总：
 * 旧版总览页把数字全写死在组件里，结果改了菜单/数据后页面就与真实情况脱节
 * （出现过「7 个一级菜单 / 20 个页面」这种残留）。派生之后，
 * 总览页的「运行中条件单 23 条」与条件单页面看到的条数**必然一致**，
 * 不会再出现两个页面对不上的情况。
 *
 * 后端接通后本文件整个删掉，改由 `/dashboard/overview` 一次返回。
 */
import type {
  DashboardDailyRow,
  DashboardOverview,
  DashboardSourceRow,
  DashboardSummary,
} from '../domain'
import { ACCOUNTS } from './account'
import { DATAHUB_NODES, STREAMS } from './datahub'
import { fmt } from './_shared'
import {
  CONDITIONALS,
  SERVERS as STRATEGY_SERVERS,
  isServerOnline as isStrategyServerOnline,
} from './strategy'
import {
  ASSIGNMENTS,
  ORDERS,
  SERVERS as TRADE_SERVERS,
  TRADES,
  isServerOnline as isTradeServerOnline,
} from './trade'

/* ------------------------------ 工具 ------------------------------ */

/** 取 YYYY-MM-DD（本地时区，与 mock 里 `fmt()` 落库的格式一致） */
function dayOf(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`
}

/** 万/亿 的紧凑写法，用于「数据来源」表的规模列 */
function compact(n: number): string {
  if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(2)} 亿条`
  if (n >= 10_000) return `${(n / 10_000).toFixed(1)} 万条`
  return `${n} 条`
}

/* ------------------------------ 汇总 ------------------------------ */

/**
 * 汇总指标 —— 全部从各模块 mock 现算。
 *
 * ⚠️ 这里算的是**全量**（mock 数组本身就是全量），不是某一页。
 * 真实后端也必须给全量口径的汇总接口，不能让前端拿分页列表自己数。
 */
function buildSummary(): DashboardSummary {
  const tradeOnline = TRADE_SERVERS.filter(isTradeServerOnline).length
  const strategyOnline = STRATEGY_SERVERS.filter(isStrategyServerOnline).length
  const datahubOnline = DATAHUB_NODES.filter((n) => n.online).length

  // 委托单里「已成交」的判定：成交表按 OrderNo 关联到了委托
  const filledOrderNos = new Set(TRADES.map((t) => t.OrderNo).filter(Boolean) as string[])
  const filled = ORDERS.filter((o) => o.OrderNo && filledOrderNos.has(o.OrderNo)).length

  const volume = TRADES.reduce((sum, t) => sum + Number(t.Volumn ?? 0), 0)

  // 消息总线：入口积压 / lag / pending / 无消费组的流
  const ingress = STREAMS.filter((s) => s.role === 'ingress')
  let lag = 0
  let pending = 0
  for (const s of STREAMS) {
    for (const g of s.groups) {
      lag += g.lag ?? 0
      pending += g.pending ?? 0
    }
  }

  return {
    nodes: {
      tradeTotal: TRADE_SERVERS.length,
      tradeOnline,
      strategyTotal: STRATEGY_SERVERS.length,
      strategyOnline,
      datahubTotal: DATAHUB_NODES.length,
      datahubOnline,
    },
    conditional: {
      total: CONDITIONALS.length,
      running: CONDITIONALS.filter((c) => c.Mode === 1).length,
      triggered: CONDITIONALS.filter((c) => c.Status === 1).length,
    },
    order: {
      total: ORDERS.length,
      filled,
    },
    trade: {
      total: TRADES.length,
      volume,
    },
    account: {
      total: ACCOUNTS.length,
      online: ACCOUNTS.filter((a) => a.onlineflag === 1).length,
    },
    stream: {
      count: STREAMS.length,
      ingressBacklog: ingress.reduce((sum, s) => sum + s.length, 0),
      consumerLag: lag,
      pending,
      // 现场 137 的入口流**没有消费组** —— 写入后没人投递，是最危险的一类
      groupLess: STREAMS.filter((s) => s.groups.length === 0).length,
    },
  }
}

/* --------------------------- 数据来源与规模 --------------------------- */

function buildSources(): DashboardSourceRow[] {
  const ingress = STREAMS.filter((s) => s.role === 'ingress')
  const reply = STREAMS.filter((s) => s.role === 'reply')
  const downstream = STREAMS.filter((s) => s.role === 'downstream' || s.role === 'ack')

  const ingressBacklog = ingress.reduce((sum, s) => sum + s.length, 0)
  const replyLen = reply.reduce((sum, s) => sum + s.length, 0)
  const downstreamLen = downstream.reduce((sum, s) => sum + s.length, 0)

  return [
    {
      name: 'DataHub_req_stream',
      kind: 'redis',
      kindLabel: 'Redis Stream',
      scale: compact(ingressBacklog),
      hint: `${ingress.length} 条入口流合计；137 上那条没有消费组，查询必须限流`,
    },
    {
      name: 'DataHub_reply_stream',
      kind: 'redis',
      kindLabel: 'Redis Stream',
      scale: compact(replyLen),
      hint: `中台回包流，共 ${reply.length} 条`,
    },
    {
      name: 'ST-<编号> / WT-<编号>',
      kind: 'redis',
      kindLabel: 'Redis Stream',
      scale: `${downstream.length} 条 / ${compact(downstreamLen)}`,
      hint: '中台 → 策略平台 / 委托服务器 下发与回执流，消费组固定 user_group',
    },
    {
      name: 'traderserver',
      kind: 'redis',
      kindLabel: 'Redis JSON',
      scale: `${TRADE_SERVERS.length} 条记录`,
      hint: `委托服务器节点清单，enable=true 且心跳新鲜的 ${TRADE_SERVERS.filter(isTradeServerOnline).length} 个`,
    },
    {
      name: 'strategy-srv',
      kind: 'redis',
      kindLabel: 'Redis JSON',
      scale: `${STRATEGY_SERVERS.length} 条记录`,
      hint: `策略服务器节点清单，含 usecount（承载条件单用户数）`,
    },
    {
      name: 'acc-* / strategy-*',
      kind: 'redis',
      kindLabel: 'Redis JSON',
      // 真实口径：只有 hasRedis=true 的账号才在 Redis 里有 acc-* 键
      // （不能用分配关系条数代替 —— 那是另一套数据，条数也不同）
      scale: `${ACCOUNTS.filter((a) => a.hasRedis).length} 个账号键`,
      hint: `账号实时态对象；另有 ${ACCOUNTS.length} 个账号（含仅 MySQL 有、无 Redis 键的）`,
    },
    {
      name: 'T_Conditional(_his)',
      kind: 'mysql',
      kindLabel: 'MySQL',
      scale: `${CONDITIONALS.length} 条`,
      hint: '条件单主表与历史表，另有 V_Conditional 视图取并集',
    },
    {
      name: 'T_Order(_his)',
      kind: 'mysql',
      kindLabel: 'MySQL',
      scale: `${ORDERS.length} 条`,
      hint: `条件单触发后产生的委托；其中 ${ORDERS.filter((o) => o.OrderNo && new Set(TRADES.map((t) => t.OrderNo)).has(o.OrderNo)).length} 条已产生成交`,
    },
    {
      name: 'T_Trade(_his)',
      kind: 'mysql',
      kindLabel: 'MySQL',
      scale: `${TRADES.length} 条`,
      hint: '成交明细，按 OrderNo 与委托单关联',
    },
    {
      name: 'T_StrategyLog',
      kind: 'mysql',
      kindLabel: 'MySQL',
      scale: '1.66 万行（现场实测）',
      hint: '策略单日志；行数为现场快照，不做演示派生',
    },
    {
      name: 'T_UserLog',
      kind: 'mysql',
      kindLabel: 'MySQL',
      scale: '19.05 万行（现场实测）',
      hint: '登录日志，查询必须分页；现场 LogIP/LogMAC/LogOS 三列全为 NULL',
    },
  ]
}

/* ------------------------------ 趋势 ------------------------------ */

/**
 * 近 14 天：条件单新增 / 委托 / 成交三条线。
 *
 * 这三条线**跨了三个业务表**，任何单独一个页面都看不到 ——
 * 这也正是总览页存在的意义（单页只能看到自己那张表）。
 */
function buildDaily(): DashboardDailyRow[] {
  const out: DashboardDailyRow[] = []
  for (let i = 13; i >= 0; i -= 1) {
    const day = dayOf(new Date(Date.now() - i * 86400_000))
    out.push({
      date: day,
      conditional: CONDITIONALS.filter((c) => (c.AddTime ?? '').startsWith(day)).length,
      order: ORDERS.filter((o) => (o.OrderTime ?? '').startsWith(day)).length,
      trade: TRADES.filter((t) => (t.TradeTime ?? '').startsWith(day)).length,
    })
  }
  return out
}

/* ----------------------------- 对外接口 ----------------------------- */

export const mockDashboardApi = {
  getOverview(): DashboardOverview {
    return {
      summary: buildSummary(),
      sources: buildSources(),
      daily: buildDaily(),
      generatedAt: fmt(new Date()),
    }
  },
}
