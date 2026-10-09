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
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)} 天前`
  return shortDateTime(typeof value === 'string' ? value : new Date(ms).toISOString())
}

/** 千分位 */
export function thousands(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-'
  return value.toLocaleString('zh-CN')
}
