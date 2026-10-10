import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { authApi } from '@/api/endpoints'
import { getToken, setToken } from '@/api/http'
import { PERMISSION_WILDCARD, hasPermission } from '@/config/permissions'
import { USE_MOCK } from '@/composables/useResource'

/**
 * 当前会话（登录人 + 权限键集合）。
 *
 * ⚠️ **这里没有假装登录成功。**
 * README 的「已知待办」里写着「登录鉴权：顶栏『退出登录』目前只弹提示，未接真实鉴权」，
 * 这一条仍然成立 —— 后端 `backend/` 还是空的，`/system/auth/login` 没人实现。
 * 所以：
 *  - 演示态（VITE_USE_MOCK=true）：给一个**明确标注为演示**的身份，
 *    权限为通配符，这样三个系统管理页的按钮可见性逻辑能真实跑起来；
 *  - 非演示态：没拿到真实资料就是「未登录」，权限为空，
 *    所有写操作的按钮一律不可用 —— 宁可点不动，也不能在没有鉴权的情况下放开高危入口。
 *
 * 后端接口就绪后，把 `login()` 接到登录页即可，页面里的权限判断都不用改。
 */

/** 演示身份：不是真实登录，界面上必须能看出来 */
const DEMO_IDENTITY = {
  username: 'admin',
  displayName: '系统管理员（演示身份）',
  roles: ['super_admin'],
  permissions: [PERMISSION_WILDCARD] as string[],
}

export interface AuthProfile {
  username: string
  displayName?: string
  roles: string[]
  permissions: string[]
}

export const useAuthStore = defineStore('auth', () => {
  /** 是否已确认真实登录态（演示身份也算「已知」，但要单独标记） */
  const profile = ref<AuthProfile | null>(USE_MOCK ? { ...DEMO_IDENTITY } : null)
  /** 当前身份是否只是演示态身份（不是真登录） */
  const isDemoIdentity = ref<boolean>(USE_MOCK)
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

  /**
   * 是否有某权限。
   *
   * 演示态放行（否则三个页面全是灰按钮，看不出效果）；
   * 非演示态**严格按权限键判定**，未登录一律拒绝。
   */
  function can(key: string): boolean {
    if (isDemoIdentity.value) return true
    return hasPermission(profile.value?.permissions, key)
  }

  /** 拉取真实资料（后端就绪后由路由守卫或 App 启动时调用） */
  async function fetchProfile(): Promise<void> {
    if (USE_MOCK) {
      profile.value = { ...DEMO_IDENTITY }
      isDemoIdentity.value = true
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
      }
      isDemoIdentity.value = false
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      profile.value = null
    } finally {
      loading.value = false
    }
  }

  /**
   * 登录。
   *
   * ⚠️ 后端未实现（`/system/auth/login` 404 → NotImplementedError）。
   * 这里**不吞异常、不伪造 token**：失败就如实抛给调用方，
   * 由登录页提示「后端接口尚未实现」。
   */
  async function login(payload: { username: string; password: string }): Promise<void> {
    const result = await authApi.login(payload)
    setToken(result.token)
    profile.value = {
      username: result.username,
      displayName: result.displayName,
      roles: result.roles ?? [],
      permissions: result.permissions ?? [],
    }
    isDemoIdentity.value = false
  }

  function logout() {
    setToken(null)
    profile.value = USE_MOCK ? { ...DEMO_IDENTITY } : null
    isDemoIdentity.value = USE_MOCK
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
    can,
    fetchProfile,
    login,
    logout,
  }
})
