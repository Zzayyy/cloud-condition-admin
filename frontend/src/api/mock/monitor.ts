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
import type { TimeSeriesPoint } from '../domain'
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
  /** 指标来源，鼠标悬停时说明 */
  source: string
}

/**
 * 只保留「积压与消费健康」三类 —— 这正是消息总线那一页在回答的问题。
 * 内存类指标归属 Redis 管理，不搬到这里。
 */
export const TREND_METRICS: TrendMetricDef[] = [
  {
    key: 'ingressBacklog',
    label: '入口流积压',
    base: 4073271 + 6057 + 6004,
    unit: '条',
    warnAt: 1_000_000,
    jitter: 0.004,
    source: 'XLEN(DataHub_req_stream) 跨实例合计',
  },
  {
    key: 'consumerLag',
    label: '消费组 lag',
    base: 37300,
    unit: '条',
    warnAt: 20_000,
    jitter: 0.12,
    source: 'XINFO GROUPS lag 合计（已写入未投递）',
  },
  {
    key: 'consumerPending',
    label: '未确认 pending',
    base: 595 + 11 + 162,
    unit: '条',
    warnAt: 500,
    jitter: 0.25,
    source: 'XINFO GROUPS pending 合计（已投递未确认）',
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
 * 生成一条趋势曲线。
 *
 * ⚠️ 现场**没有时序库**，所以演示态下的历史是「围绕当前值往回生成」的，
 * 不是真实历史。组件上必须如实标注这一点，不能让人误以为是真实曲线。
 * 真实曲线需要后端接入指标存储（Prometheus 之类）后提供。
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
