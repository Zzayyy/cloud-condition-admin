import type { EChartsOption } from 'echarts'

/**
 * 「节点活跃度分布」图 —— 委托服务器页与策略服务器页**共用**。
 *
 * <h2>为什么抽出来</h2>
 * 两页同属「服务器管理」，看的是同一件事（哪些机器还活着）。
 * 分桶阈值与配色必须一致 —— 各写一份迟早会漂移，
 * 而这类不一致在页面上几乎看不出来（只是某一段的柱子高低不同），
 * 排查时会被当成"数据问题"而不是"两个页面口径不同"。
 *
 * <h2>它回答什么</h2>
 * 「在线 0 台」这个结论有两种完全不同的含义：
 * <pre>
 *   全是 5 分钟前掉的 → 刚刚集体掉线，要立刻查
 *   全是 2 个月前      → 历史残留，清理即可
 * </pre>
 * 只给一个"离线 N 个"说不清，分桶才能区分。
 */

/** 分桶：按「最后活跃距今多久」。顺序 = 从近到远 */
export const ACTIVITY_BUCKETS: { label: string; maxSec: number; color: string }[] = [
  { label: '30 秒内（在线）', maxSec: 30, color: '#2f9e5f' },
  { label: '1 小时内', maxSec: 3_600, color: '#79b93c' },
  { label: '1 天内', maxSec: 86_400, color: '#e8a33d' },
  { label: '7 天内', maxSec: 7 * 86_400, color: '#e07a3c' },
  { label: '7 天以上', maxSec: Number.POSITIVE_INFINITY, color: '#d6455e' },
]

/** 「从未活跃」单独一桶：`lastActiveTimeLong` 缺失或 <= 0 */
const NEVER_COLOR = '#9aa0a6'

/**
 * 按活跃时间分桶并生成 ECharts option。
 *
 * @param nodes 任意带 `lastActiveTimeLong`（**秒**）的行；
 *              调用方必须传**全量**，传当前页会画成"第 1 页的分布"
 *
 * ⚠️ **不直接用后端算好的 `ageSec`**：那是**后端计算那一刻**的值，
 * 页面停留一分钟就过时；而"在线"字段前端本来就会随时间自己跳。
 * 现算才能保证图和卡片说的是同一件事。
 *
 * ⚠️ **空桶保留**（值为 0）：这样「0 台在线 / 4 台都在 7 天以上」一眼可见。
 * 过滤空桶只剩一两根柱，反而看不出"哪些区间是空的"。
 */
export function buildActivityOption(
  nodes: { lastActiveTimeLong?: number | null }[],
): EChartsOption {
  const nowSec = Date.now() / 1000
  const counts = ACTIVITY_BUCKETS.map(() => 0)
  let never = 0

  for (const row of nodes) {
    const ts = row.lastActiveTimeLong
    if (!ts || ts <= 0) {
      never += 1
      continue
    }
    const age = Math.max(0, nowSec - ts)
    const idx = ACTIVITY_BUCKETS.findIndex((b) => age <= b.maxSec)
    counts[idx < 0 ? ACTIVITY_BUCKETS.length - 1 : idx] += 1
  }

  const labels = [...ACTIVITY_BUCKETS.map((b) => b.label), '从未活跃']
  const values = [...counts, never]
  const colors = [...ACTIVITY_BUCKETS.map((b) => b.color), NEVER_COLOR]

  return {
    // bottom 留 30：给 x 轴名字腾位置（containLabel 只管刻度标签，不管 axis name）
    grid: { left: 8, right: 40, top: 8, bottom: 30, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      // 说明每根柱的含义，避免"7 天以上"被读成"7 天前上线"
      formatter: (p: unknown) => {
        const first = (p as { name?: string; value?: number }[] | undefined)?.[0]
        return `${first?.name ?? ''}<br/>节点数：${first?.value ?? 0}`
      },
    },
    xAxis: {
      type: 'value',
      name: '节点数',
      // ⚠️ 默认 nameLocation='end' 会把名字放在轴的**最右端**，
      //    横向柱状图那里正好是柱子末尾 + 数值，会被挤掉/裁掉
      nameLocation: 'middle',
      nameGap: 24,
      nameTextStyle: { fontSize: 11 },
      minInterval: 1,
    },
    yAxis: {
      type: 'category',
      data: [...labels].reverse(),
      axisLabel: { fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 20,
        data: values
          .map((v, i) => ({ value: v, itemStyle: { color: colors[i] ?? NEVER_COLOR } }))
          .reverse(),
        label: { show: true, position: 'right', fontSize: 11 },
      },
    ],
  }
}
