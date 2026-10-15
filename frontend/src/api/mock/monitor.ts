/**
 * 监控指标 —— 演示数据（供「消息总线」的趋势卡使用）。
 *
 * 背景：原先有一个独立的「实时指标」页面，其 11 项指标逐项核对后发现
 * **没有一项是别处看不到的**（lag、无消费组、在线节点数、频道数、内存等
 * 都能在消息总线 / 中台节点 / 委托服务器 / Redis 管理里找到）。
 * 唯一独有的维度是**时间趋势**，所以那个页面已删除，
 * 只把趋势卡搬到「消息总线」（积压与消费健康本就是消息流的问题）。
 *
 * 设计要点：**基线取现场实测值，曲线在其附近做有界随机游走**，
 * 这样量级不会离谱（不会出现「积压 3 条」这种与现场 407 万完全不符的假象）。
 *
 * 现场实测基线（只读探查快照）：
 *  - 入口流：137 db0 `DataHub_req_stream` 4,073,271；136 db0 6,057；137 db2 6,004
 *  - 消费组 lag 合计约 37,300（137 上 ST-15=10500、ST-42=4400、ST-35=3200…）
 *  - pending：136 db0 ST-0=595、DataHub_req_stream=11、136 db1 WT-1-reply=162
 */
import type { MonitorMetricOption, TimeSeriesPoint, TimeSeriesView } from '../domain'
import { seededRandom, type Rand } from './_shared'

const rand: Rand = seededRandom(20261014)

/* ------------------------------ 可画趋势的指标 ------------------------------ */

export interface TrendMetricDef {
  key: string
  label: string
  /** 现场实测基线 */
  base: number
  /** 单位（'条' 表示整数） */
  unit?: string
  /** 告警阈值（画成虚线参考线） */
  warnAt?: number
  /** 随机游走幅度（相对基线的比例） */
  jitter: number
  /**
   * 该指标在**真实态**下有没有可回溯的历史。
   *
   * 演示态为了看出图形效果仍会生成曲线，但这个标记会传给页面，
   * 由页面决定是否显示「非真实历史」提示 —— 不能让演示曲线被当成真数据。
   */
  hasHistory: boolean
  /** 指标来源，鼠标悬停时说明 */
  source: string
}

/**
 * 可画趋势的指标。
 *
 * ⚠️ 键名必须与后端 `MonitorService` 的常量**逐字一致**（否则真实态会报
 * 「未知指标」）。`hasHistory` 也与后端一致：
 *  - 到达速率类：能靠 Stream 条目 ID 的毫秒时间戳还原**真实历史**；
 *  - lag / pending / 积压：只有当前快照，**没有历史**。
 */
export const TREND_METRICS: TrendMetricDef[] = [
  {
    key: 'ingressRate',
    label: '入口流到达速率',
    base: 27.6,
    unit: '条/秒',
    jitter: 0.35,
    hasHistory: true,
    source: 'XRANGE(DataHub_req_stream) 按条目 ID 的毫秒时间戳分桶',
  },
  {
    key: 'totalRate',
    label: '全网到达速率',
    base: 30.0,
    unit: '条/秒',
    jitter: 0.35,
    hasHistory: true,
    source: 'XRANGE(全部 stream) 按条目 ID 分桶',
  },
  {
    key: 'consumerLag',
    label: '消费组 lag',
    base: 37300,
    unit: '条',
    jitter: 0.12,
    hasHistory: false,
    source: 'XINFO GROUPS lag 合计（当前快照，Redis 不保存历史）',
  },
  {
    key: 'consumerPending',
    label: '未确认 pending',
    base: 595 + 11 + 162,
    unit: '条',
    jitter: 0.25,
    hasHistory: false,
    source: 'XINFO GROUPS pending 合计（当前快照，Redis 不保存历史）',
  },
  {
    key: 'ingressBacklog',
    label: '入口流积压',
    base: 14129288 + 6411 + 13 + 6004,
    unit: '条',
    warnAt: 1_000_000,
    jitter: 0.004,
    hasHistory: false,
    source: 'XLEN(DataHub_req_stream) 跨实例合计（当前快照）',
  },
]

/** 当前值（在基线附近有界游走） */
const current: Record<string, number> = Object.fromEntries(
  TREND_METRICS.map((d) => [d.key, d.base]),
)

/**
 * 前进一步。
 * ⚠️ 有调用才前进 —— 不要放在 computed 里，否则图表重绘会重复累加。
 */
export function advanceTrend(): void {
  for (const d of TREND_METRICS) {
    const delta = (rand() - 0.5) * 2 * d.jitter * d.base
    let next = current[d.key] + delta
    // 限制在基线 ±25%，避免长时间轮询后漂到离谱量级
    next = Math.max(d.base * 0.75, Math.min(d.base * 1.25, next))
    current[d.key] = d.unit === '条' ? Math.round(next) : next
  }
}

/** 当前值 */
export function trendCurrent(): Record<string, number> {
  return { ...current }
}

/**
 * 生成一条趋势序列（**演示态专用**）。
 *
 * ⚠️ 现场**没有时序库**，所以这里的历史是「围绕当前值往回生成」的，
 * **不是真实历史**。组件据 `historyAvailable` 显示提示。
 *
 * 真实态下：
 *  - 条目到达速率**能**算出真历史（Stream 条目 ID 带毫秒时间戳）；
 *  - lag / pending **算不出**（只有当前快照），后端返回空 points，
 *    页面显示空态而不是曲线。
 *
 * 演示态为了看到图形效果，对没有历史的指标也生成曲线，
 * 但把 `historyAvailable` 标为 false，让页面照实提示。
 */
export function trendSeries(key: string, points = 60): TimeSeriesPoint[] {
  const def = TREND_METRICS.find((d) => d.key === key)
  const base = def ? current[def.key] : 0
  const jitter = def?.jitter ?? 0.05
  const isInt = def?.unit === '条'

  const out: TimeSeriesPoint[] = []
  const now = Date.now()
  let v = base * (1 - jitter * 0.4)
  for (let i = points - 1; i >= 0; i -= 1) {
    v += (rand() - 0.45) * jitter * base
    v = Math.max(base * 0.7, Math.min(base * 1.3, v))
    out.push({
      time: now - i * 60_000,
      value: isInt ? Math.round(v) : v,
    })
  }
  // 末点对齐当前值，避免曲线末端跳变
  out[out.length - 1] = { time: now, value: base }
  return out
}

/* ------------------------- 演示态的 /monitor 接口 ------------------------- */

/**
 * 可用指标清单（与真实后端 `GET /monitor/metrics` 同结构）。
 */
export function mockMetrics(): MonitorMetricOption[] {
  return TREND_METRICS.map((d) => ({
    key: d.key,
    label: d.label,
    unit: d.unit ?? '',
    historyAvailable: d.hasHistory,
  }))
}

/**
 * 趋势序列（与真实后端 `GET /monitor/series` 同结构）。
 *
 * ⚠️ 这里刻意**保留**「演示态对无历史指标也生成曲线」的行为，
 * 但把 `historyAvailable` 标成 false —— 这样页面那条「非真实历史」提示
 * 在演示态下也走得到，不至于上线才发现提示逻辑有问题。
 */
export function mockSeries(metric: string, window: string): TimeSeriesView {
  const def = TREND_METRICS.find((d) => d.key === metric) ?? TREND_METRICS[0]
  const windowMin = Number(String(window).replace(/[^0-9]/g, '')) || 15
  // 每分钟一个点，最多 90 个（与后端 chooseBucketSec 的点数上限一致）
  const points = Math.min(90, windowMin)
  return {
    metric: def.key,
    label: def.label,
    unit: def.unit ?? '',
    points: trendSeries(def.key, points),
    historyAvailable: def.hasHistory,
    currentValue: current[def.key] ?? def.base,
    source: def.source,
    note: def.hasHistory
      ? null
      : '演示态：该指标在真实态下没有可回溯的历史（Redis 只保存当前快照），此曲线为演示生成。',
    windowMin,
    bucketSec: 60,
  }
}
