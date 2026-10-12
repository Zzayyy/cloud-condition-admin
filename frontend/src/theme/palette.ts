/**
 * 模块主色 —— 让一级菜单在视觉上互相区分。
 *
 * 这套颜色不只是装饰：侧边栏图标、页面标题色块、页签小圆点、
 * 总览卡片、占位页强调色都按模块取色，用户扫一眼就能知道自己在哪个模块。
 *
 * ⚠️ **键必须与 `config/menu.ts` 的一级菜单 name 一致** —— `moduleKeyFromPath`
 * 用路径首段反查，名字对不上就回退到品牌蓝，多个顶级菜单会撞成一个颜色。
 * 当前一级菜单：dashboard / server / bizdata / account / storage / system。
 *
 * `monitor` 保留（一级菜单「监控与统计」已删除，但消息总线页的趋势卡
 * 仍按红色系取色，`monitorApi` 也还在），删掉会让那处回退到品牌蓝。
 */
const MODULE_COLORS: Record<string, string> = {
  dashboard: '#3b6ff5', // 蓝
  server: '#0d9c9c', // 青
  bizdata: '#7c5cf0', // 紫（四个业务表页面共用，它们同属「业务数据」分组）
  account: '#d94f8a', // 玫红
  storage: '#2f9e5f', // 绿
  monitor: '#d6455e', // 红（仅趋势卡使用，无一级菜单）
  system: '#5b6b8c', // 石板灰
}

/** 品牌主色 */
export const PRIMARY = '#3b6ff5'

/** #rgb / #rrggbb → rgba(...)，用于生成同色系的浅底与描边。 */
export function hexToRgba(hex: string, alpha: number): string {
  const raw = hex.replace('#', '')
  const full =
    raw.length === 3
      ? raw
          .split('')
          .map((c) => c + c)
          .join('')
      : raw
  const value = Number.parseInt(full, 16)
  const r = (value >> 16) & 255
  const g = (value >> 8) & 255
  const b = value & 255
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** 从路径反查所属模块，如 /strategy/server → strategy。 */
export function moduleKeyFromPath(path: string): string | undefined {
  const segment = path.split('/').filter(Boolean)[0]
  return segment && segment in MODULE_COLORS ? segment : undefined
}

/** 取模块主色；未知模块回退到品牌主色。 */
export function accentOf(key?: string | null): string {
  if (!key) return PRIMARY
  return MODULE_COLORS[key] ?? PRIMARY
}

/** 取模块同色系的半透明色（背景/描边用）。 */
export function tintOf(key: string | null | undefined, alpha: number): string {
  return hexToRgba(accentOf(key), alpha)
}

export { MODULE_COLORS }
