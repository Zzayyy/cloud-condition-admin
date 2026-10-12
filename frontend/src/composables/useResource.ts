import { computed, ref, shallowRef, type ComputedRef, type Ref, type ShallowRef } from 'vue'

import { BizError, NotImplementedError } from '@/api/http'

export interface UseResourceOptions<T, P> {
  /**
   * 演示数据工厂。仅当「演示数据开关」打开、或后端确实不可达时才会被调用。
   */
  mock?: (params: P) => T | Promise<T>
}

export interface UseResourceReturn<T, P> {
  data: ShallowRef<T | undefined>
  loading: Ref<boolean>
  /** 真实错误（后端答了但失败，或代码异常） */
  error: Ref<string | undefined>
  /** 当前展示的是否为演示数据 */
  isMock: Ref<boolean>
  /**
   * **判断「该走演示分支还是真实分支」时请用这个，不要直接用 `isMock`。**
   *
   * 原因：`isMock` 初值是 `false`，只有在 `load()` 的 `await` **之后**才会被置位。
   * 页面在 `onMounted` 里 `void reload()`（不 await）时，首个请求返回前
   * `isMock.value` 仍是 `false` —— 此刻用户点写操作按钮，就会**误判为真实态**、
   * 去调还没实现的后端接口、然后弹一个「失败」。
   * 演示延迟（mock 的 `withDelay` 默认 260ms）让这个窗口真实存在。
   *
   * 语义：数据还没到手时退回构建期常量 `USE_MOCK`（没有时序问题）；
   * 数据到手后以 `isMock` 为准（它还能识别「自动降级到演示数据」的中间态）。
   * 已实测：`load()` 后 await 前 `isMock===false`、`loading===true`、`apiCalls===0`。
   */
  usingMock: ComputedRef<boolean>
  /** 是否因为后端不可达而自动降级 */
  isDegraded: Ref<boolean>
  load: (params: P) => Promise<void>
}

/**
 * 演示数据总开关（.env 里的 VITE_USE_MOCK）。
 *
 * 后端还没写，所以开发环境默认打开；接口落地后把它改成 false，
 * 页面立刻改走真实接口，不需要改任何页面代码。
 *
 * 这里刻意用**显式开关**而不是靠 HTTP 状态码猜：
 * 生产环境后端返回 500 往往是真故障，拿假数据盖住会掩盖问题。
 */
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

// 生产环境若误开了演示开关，大声报警 —— 这是管理真实交易的系统，
// 不能让假数据被当成真的。
if (!import.meta.env.DEV && USE_MOCK) {
  console.error(
    '[云条件单后台] 严重配置错误：生产构建里 VITE_USE_MOCK=true，页面会展示假数据！' +
      '请检查 .env.production 并重新构建。',
  )
}

/** 判断错误是否属于「后端根本不可达」——只有这种情况才允许降级。 */
function isUnreachable(err: unknown): boolean {
  if (err instanceof NotImplementedError) return true
  // 业务错误码说明后端答了，属于真错误，绝不降级
  if (err instanceof BizError) return false
  if (err instanceof Error) {
    // 代理目标没起 / 网络不通 / 超时，都会走到这里
    return /网络错误|Network Error|ERR_NETWORK|ERR_CONNECTION|Failed to fetch|ECONNREFUSED|ECONNRESET|timeout|HTTP 50[0-9]/i.test(
      err.message,
    )
  }
  return false
}

/**
 * 是否允许「自动降级到演示数据」。
 *
 * **只在开发环境开启。** 生产环境必须关掉：这是管理真实交易系统的后台，
 * 后端偶发 500 时若悄悄拿假数据顶上，运维可能据此做出错误判断。
 * 生产环境宁可报错，也不给假数据。
 */
const ALLOW_DEGRADE = import.meta.env.DEV

function describe(err: unknown): string {
  if (err instanceof NotImplementedError) return '后端接口尚未实现'
  if (err instanceof Error) return err.message
  return String(err)
}

/**
 * 取数：真实接口优先，演示数据兜底。
 *
 * 行为分三种，页面上都能看出来当前处于哪种：
 *  1. 开关打开        → 直接用演示数据，横幅说明「已开启演示开关」
 *  2. 开关关闭 + 可达 → 用真实数据
 *  3. 开关关闭 + 不可达 → 降级到演示数据，横幅说明原因
 * 后端答了但业务失败（BizError）永远走 error，不降级。
 */
export function useResource<T, P>(
  apiCall: (params: P) => Promise<T>,
  options: UseResourceOptions<T, P> = {},
): UseResourceReturn<T, P> {
  const data = shallowRef<T>()
  const loading = ref(false)
  const error = ref<string>()
  const isMock = ref(false)
  const isDegraded = ref(false)
  const settled = ref(false)

  /**
   * 判断「演示分支还是真实分支」的正确姿势（见 `UseResourceReturn.usingMock` 的说明）。
   * 数据未到手时退回构建期常量 `USE_MOCK`，避开 `isMock` 初值 false 的时序窗口。
   */
  const usingMock = computed(() => (settled.value ? isMock.value : USE_MOCK))

  async function load(params: P) {
    loading.value = true
    error.value = undefined

    try {
      if (USE_MOCK && options.mock) {
        data.value = await options.mock(params)
        isMock.value = true
        isDegraded.value = false
        return
      }

      data.value = await apiCall(params)
      isMock.value = false
      isDegraded.value = false
    } catch (err) {
      if (ALLOW_DEGRADE && options.mock && isUnreachable(err)) {
        data.value = await options.mock(params)
        isMock.value = true
        isDegraded.value = true
      } else {
        error.value = describe(err)
        data.value = undefined
        isMock.value = false
      }
    } finally {
      // `settled` 必须在 `isMock` 落定之后置位，否则 usingMock 会短暂读到旧值
      settled.value = true
      loading.value = false
    }
  }

  return { data, loading, error, isMock, usingMock, isDegraded, load }
}
