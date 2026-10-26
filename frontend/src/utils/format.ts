/** 通用格式化工具。 */

/** 把 JSON 字符串格式化；解析失败就原样返回（不抛错）。 */
export function formatJson(raw: string | null | undefined): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** 'YYYY-MM-DD HH:mm:ss' → 'YYYY-MM-DD HH:mm' */
export function shortDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  return value.length >= 16 ? value.slice(0, 16) : value
}

/** 空值统一显示成 '-'，避免表格里出现 null */
export function orDash(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

/**
 * 相对时间：'刚刚' / '3 分钟前' / '2 小时前' / '5 天前'
 * 传入秒级时间戳或日期字符串。
 */
export function relativeTime(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '-'
  const ms = typeof value === 'number' ? value * 1000 : Date.parse(String(value).replace(' ', 'T'))
  if (Number.isNaN(ms)) return '-'

  const diff = Math.floor((Date.now() - ms) / 1000)
  if (diff < 0) return '刚刚'
  if (diff < 60) return `${diff} 秒前`
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`

  const days = Math.floor(diff / 86400)
  if (days < 30) return `${days} 天前`

  /*
   * ⚠️ 超过 30 天原来走的是：
   *      shortDateTime(new Date(ms).toISOString())
   *    那有两个问题：
   *      1. `toISOString()` 是 **UTC**，比北京时间早 8 小时 —— 表格里写
   *         `2026-08-12T08:39`，而同一行的 tooltip（原始 last-active-time）
   *         写 `2026-08-12 16:39`，两个数字互相打架；
   *      2. 截断后还留着 ISO 的 `T`，格式和项目里其它时间不一致。
   *    现在统一用相对时间：**"2 个月前" 比 "2026-08-12T08:39" 更有信息量**
   *    （看服务器列表时，关心的是"多久没动了"，不是具体哪一天）。
   *    需要精确时刻的，tooltip 里一直有原始字段。
   */
  if (days < 365) return `${Math.floor(days / 30)} 个月前`
  return `${Math.floor(days / 365)} 年前`
}

/** 千分位 */
export function thousands(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toLocaleString('zh-CN')
}
