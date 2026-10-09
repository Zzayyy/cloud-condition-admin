/**
 * 演示数据的公共工具。
 *
 * 供各模块的 mock 复用，避免每个文件重复实现「确定性随机 / 分页 / 时间格式化」。
 * 后端接通后本目录整个删掉。
 */
import type { PageQuery, PageResult } from '../types'

/** 固定种子的伪随机，保证每次刷新数据一致，方便对比界面。 */
export function seededRandom(seed: number) {
  let s = seed
  return function next() {
    s |= 0
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export type Rand = () => number

export function pick<T>(rand: Rand, list: readonly T[]): T {
  return list[Math.floor(rand() * list.length)]
}

export function intBetween(rand: Rand, min: number, max: number): number {
  return min + Math.floor(rand() * (max - min + 1))
}

export function floatBetween(rand: Rand, min: number, max: number, digits = 4): string {
  return (rand() * (max - min) + min).toFixed(digits)
}

/** 'YYYY-MM-DD HH:mm:ss' */
export function fmt(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ` +
    `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`
  )
}

/** 'YYYY-MM-DD' */
export function fmtDate(date: Date): string {
  return fmt(date).slice(0, 10)
}

/** 相对现在偏移若干秒，返回 [格式化字符串, 秒级时间戳] */
export function agoBy(seconds: number): [string, number] {
  const d = new Date(Date.now() - seconds * 1000)
  return [fmt(d), Math.floor(d.getTime() / 1000)]
}

/** 模拟网络延迟，让加载态可见 */
export function withDelay<T>(value: T, ms = 260): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

/**
 * 通用查询器：过滤 → 排序 → 分页。
 * `filters` 里每个键都是一个判定函数，返回 false 即过滤掉。
 */
export function paginate<T>(
  rows: T[],
  query: PageQuery,
  search: (row: T, keyword: string) => boolean,
  sorters: Record<string, (row: T) => number | string>,
  filters: Array<(row: T, query: PageQuery) => boolean> = [],
): PageResult<T> {
  const page = Number(query.page) > 0 ? Number(query.page) : 1
  const pageSize = Number(query.pageSize) > 0 ? Number(query.pageSize) : 20

  let list = rows
  for (const f of filters) list = list.filter((row) => f(row, query))

  const keyword = typeof query.keyword === 'string' ? query.keyword.trim() : ''
  if (keyword) list = list.filter((row) => search(row, keyword))

  const sortBy = typeof query.sortBy === 'string' ? query.sortBy : ''
  const sorter = sorters[sortBy]
  if (sorter) {
    const dir = query.sortOrder === 'desc' ? -1 : 1
    list = [...list].sort((a, b) => {
      const va = sorter(a)
      const vb = sorter(b)
      if (va === vb) return 0
      return (va > vb ? 1 : -1) * dir
    })
  }

  const total = list.length
  const start = (page - 1) * pageSize
  return { list: list.slice(start, start + pageSize), total, page, pageSize }
}

/* --------------------------- 查询参数小工具 --------------------------- */

export const asString = (v: unknown): string => (typeof v === 'string' ? v : '')

export const asNumber = (v: unknown): number | undefined => {
  if (v === '' || v === null || v === undefined) return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}
