import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi } from '@/api/endpoints'
import { BizError, getToken, setToken } from '@/api/http'
import { mockAdminApi } from '@/api/mock/system'
import { PERMISSION_WILDCARD, hasPermission } from '@/config/permissions'
import { USE_MOCK } from '@/composables/useResource'

/**
 * 当前会话（登录人 + 权限键集合）。
 *
 * ⚠️ **这里不再自动登录。**
 *
 * 早先的版本在演示态直接塞了一个通配符身份，好处是三个系统管理页的按钮可见性
 * 能立刻跑起来；坏处是**登录页永远不会出现**，而且「未登录」这条路径
 * 从来没被走过 —— 权限判定里最容易错的就是这条路径。
 * 现在演示态也必须**真的登录一次**（凭据见 `src/api/mock/system.ts` 的密码表）。
 *
 * 三点刻意的设计：
 *  1. **登录态持久化到 localStorage**：刷新页面不该被踢回登录页。
 *     真实态存 token；演示态额外存一份身份快照（演示态没有真 token 可换资料）。
 *  2. **未登录 = 权限为空**：`can()` 一律 false，写操作按钮全部点不动。
 *     宁可点不动，也不能在没有鉴权的情况下放开高危入口。
 *  3. **不伪造成功**：演示态密码校验由 mock 承担，失败按 `code` 如实报错；
 *     真实态调 `/system/auth/login`，后端未实现时如实抛出，不假装登录上了。
 */

const TOKEN_KEY = 'cca:token'
/** 演示态身份快照（真实态不用它，靠 token 换 `/system/auth/profile`） */
const DEMO_PROFILE_KEY = 'cca:demo-profile'

export interface AuthProfile {
  username: string
  displayName?: string
  roles: string[]
  permissions: string[]
  mustChangePassword?: boolean
}

/** 登录失败原因（登录页据此分派文案，不去匹配 message 字符串） */
export interface LoginFailure {
  message: string
  /** 业务错误码，见 `domain.ts` 的 `LOGIN_ERROR_CODES` */
  code?: number
  /** 是否属于「后端根本连不上 / 未实现」，用于提示「待对接」而不是「账号错了」 */
  unreachable: boolean
}

function readDemoProfile(): AuthProfile | null {
  try {
    const raw = localStorage.getItem(DEMO_PROFILE_KEY)
    return raw ? (JSON.parse(raw) as AuthProfile) : null
  } catch {
    // 存储被写坏时当作未登录，不要因为一段脏 JSON 让整个应用起不来
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  /**
   * 初始身份：**没有任何身份**，除非上次登录过且痕迹还在。
   * 演示态与真实态在这里的判断不同，但共同点是「不主动塞一个假的登录态」。
   */
  const profile = ref<AuthProfile | null>(
    USE_MOCK ? (getToken() ? readDemoProfile() : null) : null,
  )
  /** 当前身份是否来自演示登录（不是真实后端签发） */
  const isDemoIdentity = ref<boolean>(USE_MOCK && profile.value !== null)
  const loading = ref(false)
  const error = ref<string>()

  const isLoggedIn = computed(() => profile.value !== null)
  const username = computed(() => profile.value?.username ?? '未登录')
  const displayName = computed(
    () => profile.value?.displayName ?? (profile.value ? profile.value.username : '未登录'),
  )
  const roleNames = computed(() => profile.value?.roles ?? [])
  const isSuperAdmin = computed(
    () => profile.value?.permissions.includes(PERMISSION_WILDCARD) ?? false,
  )
  const mustChangePassword = computed(() => profile.value?.mustChangePassword === true)

  /**
   * 是否有某权限。
   *
   * **未登录一律 false**；已登录则严格按权限键判定（`*` 覆盖一切）。
   * 演示态登录拿到的也是**真实权限键**（不再无条件放行），
   * 这样「换个账号登录 → 按钮变灰」这件事才真的能演示出来。
   */
  function can(key: string): boolean {
    return hasPermission(profile.value?.permissions, key)
  }

  /** 记住身份（演示态写快照，真实态靠 token） */
  function persist(next: AuthProfile, demo: boolean) {
    profile.value = next
    isDemoIdentity.value = demo
    if (demo) localStorage.setItem(DEMO_PROFILE_KEY, JSON.stringify(next))
  }

  function clear() {
    setToken(null)
    localStorage.removeItem(DEMO_PROFILE_KEY)
    profile.value = null
    isDemoIdentity.value = false
  }

  /**
   * 登录。
   *
   * 演示态：走 `mockAdminApi.login`（真校验，含停用/锁定/失败计数）；
   * 真实态：调 `/system/auth/login`。
   *
   * 成功后写入 token 与身份；失败**如实抛出** `LoginFailure`，
   * 由登录页决定怎么显示 —— 这里绝不吞异常、绝不伪造 token。
   */
  async function login(payload: { username: string; password: string }): Promise<AuthProfile> {
    loading.value = true
    error.value = undefined
    try {
      if (USE_MOCK) {
        // 演示延迟：让「正在登录」这个状态可见，而不是瞬间闪过去
        await new Promise((resolve) => setTimeout(resolve, 420))
        const result = mockAdminApi.login(payload.username, payload.password)
        setToken(result.token)
        const next: AuthProfile = {
          username: result.username,
          displayName: result.displayName,
          roles: result.roles,
          permissions: result.permissions,
          mustChangePassword: result.mustChangePassword,
        }
        persist(next, true)
        return next
      }

      const result = await authApi.login(payload)
      setToken(result.token)
      const next: AuthProfile = {
        username: result.username,
        displayName: result.displayName,
        roles: result.roles ?? [],
        permissions: result.permissions ?? [],
        mustChangePassword: result.mustChangePassword,
      }
      persist(next, false)
      return next
    } catch (err) {
      const failure = describeLoginError(err)
      error.value = failure.message
      throw failure
    } finally {
      loading.value = false
    }
  }

  /** 把各种异常归一成登录页能直接用的结构 */
  function describeLoginError(err: unknown): LoginFailure {
    if (err instanceof BizError) {
      return { message: err.message, code: err.code, unreachable: false }
    }
    if (err instanceof Error) {
      // 代理目标没起 / 网络不通 / 后端 404 未实现，都归到「待对接」
      const unreachable = /网络错误|Network Error|ERR_NETWORK|ERR_CONNECTION|Failed to fetch|ECONNREFUSED|ECONNRESET|timeout|HTTP 50[0-9]|尚未实现|未实现/i.test(
        err.message,
      )
      return {
        message: unreachable
          ? '登录接口尚未就绪（后端 /system/auth/login 未实现或不可达）'
          : err.message,
        unreachable,
      }
    }
    return { message: String(err), unreachable: false }
  }

  /**
   * 拉取真实资料（路由守卫在「有 token 但内存里没有身份」时调用，
   * 覆盖「刷新页面」与「真实登录后重进」两种情况）。
   */
  async function fetchProfile(): Promise<void> {
    if (USE_MOCK) {
      // 演示态没有真 token 可换资料，读出上次登录的快照即可
      if (!getToken()) return
      const cached = readDemoProfile()
      if (cached) {
        profile.value = cached
        isDemoIdentity.value = true
      } else {
        clear()
      }
      return
    }

    if (!getToken()) {
      profile.value = null
      return
    }
    loading.value = true
    error.value = undefined
    try {
      const me = await authApi.profile()
      profile.value = {
        username: me.username,
        displayName: me.displayName,
        roles: me.roles ?? [],
        permissions: me.permissions ?? [],
        mustChangePassword: me.mustChangePassword,
      }
      isDemoIdentity.value = false
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      // token 失效：清干净，让守卫把用户送回登录页
      clear()
    } finally {
      loading.value = false
    }
  }

  /** 登出：清本地凭证。真实态另外通知后端（失败也不阻塞登出） */
  async function logout(): Promise<void> {
    const wasReal = !isDemoIdentity.value
    clear()
    if (wasReal && !USE_MOCK) {
      try {
        await authApi.logout()
      } catch {
        // 后端登出失败不影响本地已清凭证 —— 不能因为接口挂了就登不出去
      }
    }
  }

  return {
    profile,
    isDemoIdentity,
    loading,
    error,
    isLoggedIn,
    username,
    displayName,
    roleNames,
    isSuperAdmin,
    mustChangePassword,
    can,
    fetchProfile,
    login,
    logout,
  }
})

export { TOKEN_KEY as AUTH_TOKEN_KEY }
