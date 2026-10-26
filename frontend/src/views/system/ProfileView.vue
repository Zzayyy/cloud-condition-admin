<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NSpin,
  NTag,
  useMessage,
} from 'naive-ui'
import { KeyOutline, PersonOutline, ShieldOutline } from '@vicons/ionicons5'

import { authApi } from '@/api/endpoints'
import { USE_MOCK } from '@/composables/useResource'
import { useAuthStore } from '@/stores/auth'
import {
  ACTION_PERMISSIONS,
  ACTION_PREFIX,
  MENU_PREFIX,
  PERMISSION_WILDCARD,
  actionPermissionKey,
} from '@/config/permissions'
import { flattenMenu, menuTree } from '@/config/menu'

/**
 * 个人信息页。
 *
 * <h2>它同时是一个「出口」</h2>
 * 后端从建表起就有 `mustChangePassword`（「下次登录必须改密」）：
 * 新建账号默认置 1，管理员重置密码后可置 1，用户自己改完置 0。
 * 但前端**一直没有这个字段的消费方，也没有改密入口** ——
 * 于是标记永远清不掉，强制改密也就从未真正生效过。
 *
 * 所以这个页面是那个机制的落点：先有地方改，守卫才敢拦。
 * 顺序不能反 —— 先拦再给入口会把人锁在外面。
 */

const accent = '#3b6ff5'
const auth = useAuthStore()
const router = useRouter()
const message = useMessage()

/**
 * 是否处于「必须改密」状态。
 *
 * ⚠️ 演示态一律为 false：mock 里没有改密实现，
 * 真拦进来用户也改不了，只能卡死。
 */
const forced = computed(() => !USE_MOCK && auth.mustChangePassword)

/* ------------------------------ 基本信息 ------------------------------ */

const roles = computed(() => auth.roleNames)

/** 菜单 name → 页面标题。权限键里只有 name，展示时得换成中文 */
const menuTitles = new Map<string, string>()
for (const node of flattenMenu(menuTree)) {
  menuTitles.set(node.name, node.meta.title)
}

/**
 * 权限键 → 可读文案。
 *
 * 直接铺一堆 `menu:conditional` / `action:conditional:write` 对运维没有意义，
 * 而「我能操作什么」恰恰是个人信息页最该回答的问题之一。
 */
function permissionLabel(key: string): string {
  if (key === PERMISSION_WILDCARD) return '全部权限（超级管理员）'
  if (key.startsWith(MENU_PREFIX)) {
    const name = key.slice(MENU_PREFIX.length)
    return `页面 · ${menuTitles.get(name) ?? name}`
  }
  if (key.startsWith(ACTION_PREFIX)) {
    const def = ACTION_PERMISSIONS.find((a) => actionPermissionKey(a.menu, a.action) === key)
    return def ? `操作 · ${def.label}` : key
  }
  return key
}

/** 高危操作权限单独标红：让本人知道自己握着什么 */
const dangerousKeys = new Set(
  ACTION_PERMISSIONS.filter((a) => a.dangerous).map((a) => actionPermissionKey(a.menu, a.action)),
)

const permissions = computed(() =>
  (auth.profile?.permissions ?? []).map((key) => ({
    key,
    label: permissionLabel(key),
    dangerous: dangerousKeys.has(key),
  })),
)

/* ------------------------------ 修改密码 ------------------------------ */

const form = ref({ oldPassword: '', newPassword: '', confirm: '' })
const submitting = ref(false)

/**
 * 前端只做**体验层**校验（空值、两次不一致、新密码没用）；
 * 「不能与历史密码重复」「复杂度策略」以后端为准 —— 那是唯一权威。
 * 这里不重复实现规则，避免两边规则漂移后前端拦、后端放行。
 */
const mismatch = computed(
  () => form.value.confirm.length > 0 && form.value.newPassword !== form.value.confirm,
)

const canSubmit = computed(
  () =>
    form.value.oldPassword.length > 0 &&
    form.value.newPassword.length > 0 &&
    form.value.newPassword === form.value.confirm &&
    form.value.newPassword !== form.value.oldPassword,
)

async function submit() {
  if (!canSubmit.value || submitting.value) return

  if (USE_MOCK) {
    message.warning('演示态不提供改密：mock 里没有密码库，请连真实后端验证')
    return
  }

  submitting.value = true
  try {
    await authApi.changePassword({
      oldPassword: form.value.oldPassword,
      newPassword: form.value.newPassword,
    })
    /*
     * ⚠️ 后端改密后会让旧 token 立刻失效（sessionKicker.kick），
     * 所以这里不能"改完继续用"，必须清身份回登录页。
     * 否则下一次请求会 401，用户会以为"改密码把系统改坏了"。
     */
    message.success('密码已修改，请用新密码重新登录')
    await auth.logout()
    router.replace({ name: 'login' })
  } catch (err) {
    message.error(`修改失败：${(err as Error)?.message ?? '未知错误'}`)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <n-alert v-if="forced" type="warning" :bordered="false" style="margin-bottom: 14px">
      当前密码由管理员设置，<b>必须修改后才能继续使用系统</b>。请先在下方「修改密码」里换掉。
    </n-alert>

    <n-spin :show="auth.loading">
      <!-- 基本信息 -->
      <n-card class="page-card" size="small" :bordered="false">
        <template #header>
          <span class="section-title">
            <n-icon :component="PersonOutline" :color="accent" :size="17" />
            基本信息
          </span>
        </template>
        <n-descriptions :column="2" label-placement="left" size="small" bordered>
          <n-descriptions-item label="登录名">
            <span class="mono">{{ auth.username }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="显示名">
            {{ auth.displayName }}
          </n-descriptions-item>
          <n-descriptions-item label="角色">
            <template v-if="roles.length">
              <n-tag v-for="r in roles" :key="r" size="small" :bordered="false" style="margin-right: 6px">
                {{ r }}
              </n-tag>
            </template>
            <span v-else class="text-muted">未分配角色</span>
          </n-descriptions-item>
          <n-descriptions-item label="身份来源">
            <!-- 演示登录不拿真 token，必须标出来，否则会被当成真实权限 -->
            <n-tag v-if="auth.isDemoIdentity" size="small" type="warning" :bordered="false">
              演示登录（权限来自 mock，不代表后端）
            </n-tag>
            <n-tag v-else size="small" type="success" :bordered="false">真实后端签发</n-tag>
          </n-descriptions-item>
        </n-descriptions>
      </n-card>

      <!-- 修改密码 -->
      <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
        <template #header>
          <span class="section-title">
            <n-icon :component="KeyOutline" :color="accent" :size="17" />
            修改密码
          </span>
        </template>
        <template #header-extra>
          <span class="text-muted" style="font-size: 12px">
            改密成功后旧登录状态会立即失效，需要重新登录
          </span>
        </template>

        <n-form label-placement="left" label-width="88" size="small" style="max-width: 460px">
          <n-form-item label="当前密码">
            <n-input
              v-model:value="form.oldPassword"
              type="password"
              show-password-on="click"
              placeholder="输入现在用的密码"
              :input-props="{ autocomplete: 'current-password' }"
            />
          </n-form-item>
          <n-form-item label="新密码">
            <n-input
              v-model:value="form.newPassword"
              type="password"
              show-password-on="click"
              placeholder="复杂度规则以后端密码策略为准"
              :input-props="{ autocomplete: 'new-password' }"
            />
          </n-form-item>
          <n-form-item label="确认新密码" :validation-status="mismatch ? 'error' : undefined">
            <n-input
              v-model:value="form.confirm"
              type="password"
              show-password-on="click"
              placeholder="再输一次新密码"
              :input-props="{ autocomplete: 'new-password' }"
            />
          </n-form-item>
          <n-form-item label=" ">
            <n-button type="primary" size="small" :disabled="!canSubmit" :loading="submitting" @click="submit">
              保存并重新登录
            </n-button>
            <span v-if="mismatch" class="text-muted" style="margin-left: 12px; font-size: 12px">
              两次输入的新密码不一致
            </span>
          </n-form-item>
        </n-form>
      </n-card>

      <!-- 我的权限 -->
      <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
        <template #header>
          <span class="section-title">
            <n-icon :component="ShieldOutline" :color="accent" :size="17" />
            我的权限
          </span>
        </template>
        <template #header-extra>
          <span class="text-muted" style="font-size: 12px">
            共 {{ permissions.length }} 项；红色为高危操作
          </span>
        </template>

        <div v-if="permissions.length" class="perm-wrap">
          <n-tag
            v-for="p in permissions"
            :key="p.key"
            size="small"
            :bordered="false"
            :type="p.dangerous ? 'error' : 'default'"
            :title="p.key"
          >
            {{ p.label }}
          </n-tag>
        </div>
        <div v-else class="text-muted" style="font-size: 13px">
          没有任何权限 —— 请联系管理员分配角色。
        </div>
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped>
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.perm-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
