<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCheckbox, NIcon, NInput, useMessage } from 'naive-ui'
import {
  ArrowForwardOutline,
  CloudOutline,
  GitNetworkOutline,
  LockClosedOutline,
  PersonOutline,
  PulseOutline,
  ShieldCheckmarkOutline,
  TrendingUpOutline,
} from '@vicons/ionicons5'

import { DEMO_LOGIN_HINTS } from '@/api/mock/system'
import { USE_MOCK } from '@/composables/useResource'
import { firstLeafPath } from '@/config/menu'
import { firstAllowedPath } from '@/config/permissions'
import { useAuthStore, type LoginFailure } from '@/stores/auth'

/**
 * 登录页。
 *
 * 视觉取「云条件单」的两层意象：
 *  - **云** —— 极光式柔和渐层背景 + 克制的光晕；
 *  - **条件单** —— 细网格与「价格穿越阈值线触发」的走势图，
 *    这正是条件单在做的事（满足条件 → 触发委托）。
 *
 * 版式上刻意做减法：一种强调色、两级字号、大量留白，
 * 靠字重与字距而不是靠颜色堆叠来撑质感。
 */

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const message = useMessage()

const username = ref('')
const password = ref('')
const remember = ref(true)
const submitting = ref(false)
/** 登录失败提示（含「接口未就绪」这种非账号错误） */
const failure = ref<LoginFailure | null>(null)

const REMEMBER_KEY = 'cca:last-username'

onMounted(() => {
  const last = localStorage.getItem(REMEMBER_KEY)
  if (last) username.value = last
})

const canSubmit = computed(
  () => username.value.trim().length > 0 && password.value.length > 0 && !submitting.value,
)

async function handleSubmit() {
  if (!canSubmit.value) return
  failure.value = null
  submitting.value = true
  try {
    await authStore.login({ username: username.value.trim(), password: password.value })

    if (remember.value) localStorage.setItem(REMEMBER_KEY, username.value.trim())
    else localStorage.removeItem(REMEMBER_KEY)

    message.success(`欢迎回来，${authStore.displayName}`)
    // 登录前想去的页面（被守卫拦下时写在 query 里），否则进第一个**有权访问**的页面。
    // 不能用静态的 firstLeafPath()：它可能指向这个人看不到的页面，进去就被守卫弹回来。
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(
      redirect || firstAllowedPath(authStore.profile?.permissions) || firstLeafPath(),
    )
  } catch (err) {
    failure.value = err as LoginFailure
    // 密码清掉、账号留着：重试时不用重新输一遍账号
    password.value = ''
  } finally {
    submitting.value = false
  }
}

/** 点演示账号直接填表，省去手输 */
function useDemo(hint: (typeof DEMO_LOGIN_HINTS)[number]) {
  username.value = hint.username
  password.value = hint.password
  failure.value = null
}

const highlights = [
  { icon: GitNetworkOutline, title: '策略平台 · 委托服务器', desc: '节点状态与承载关系' },
  { icon: PulseOutline, title: '数据中台 · 消息总线', desc: '积压 / lag / pending 实时可见' },
  { icon: ShieldCheckmarkOutline, title: '账户 · 存储 · 审计', desc: '高危操作二次确认并留痕' },
]
</script>

<template>
  <div class="login">
    <!-- 背景：极光渐层 + 细网格（网格是「条件单/策略」的精度意象） -->
    <div class="bg" aria-hidden="true">
      <div class="bg-grid" />
      <div class="aurora aurora-1" />
      <div class="aurora aurora-2" />
      <div class="aurora aurora-3" />
    </div>

    <div class="shell">
      <!-- ============================ 左侧：品牌 ============================ -->
      <section class="brand">
        <div class="brand-top">
          <div class="logo-mark">云</div>
          <div class="logo-text">
            <div class="logo-title">云条件单</div>
            <div class="logo-sub">CLOUD CONDITIONAL ORDER</div>
          </div>
        </div>

        <div class="brand-main">
          <h1 class="headline">
            让每一个条件<br />
            <span class="hl">都被准确触发</span>
          </h1>
          <p class="sub">
            策略平台、委托服务器、数据中台与账户存储的统一后台。
          </p>

          <!-- 条件单意象：价格穿越阈值线 → 触发 -->
          <div class="spark" aria-hidden="true">
            <svg viewBox="0 0 420 132" preserveAspectRatio="none">
              <defs>
                <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="currentColor" stop-opacity="0.22" />
                  <stop offset="100%" stop-color="currentColor" stop-opacity="0" />
                </linearGradient>
              </defs>
              <!-- 阈值线 -->
              <line class="spark-threshold" x1="0" y1="46" x2="420" y2="46" />
              <!-- 触发点竖线 -->
              <line class="spark-trigger" x1="286" y1="12" x2="286" y2="120" />
              <!-- 面积 -->
              <path
                class="spark-area"
                d="M0,104 L40,96 L78,100 L112,84 L150,88 L186,70 L222,76 L258,58 L286,46 L320,34 L360,26 L420,18 L420,132 L0,132 Z"
              />
              <!-- 走势 -->
              <path
                class="spark-line"
                d="M0,104 L40,96 L78,100 L112,84 L150,88 L186,70 L222,76 L258,58 L286,46 L320,34 L360,26 L420,18"
              />
              <!-- 触发点 -->
              <circle class="spark-dot-halo" cx="286" cy="46" r="9" />
              <circle class="spark-dot" cx="286" cy="46" r="3.6" />
            </svg>
            <div class="spark-tag">条件满足 · 触发委托</div>
          </div>

          <ul class="highlights">
            <li v-for="h in highlights" :key="h.title">
              <n-icon :component="h.icon" :size="15" class="hl-icon" />
              <div>
                <div class="hl-title">{{ h.title }}</div>
                <div class="hl-desc">{{ h.desc }}</div>
              </div>
            </li>
          </ul>
        </div>

        <div class="brand-foot">
          <n-icon :component="CloudOutline" :size="13" />
          <span>内部管理系统 · 仅授权人员使用</span>
        </div>
      </section>

      <!-- ============================ 右侧：表单 ============================ -->
      <section class="panel">
        <div class="card">
          <header class="card-head">
            <h2>登录</h2>
            <p>请使用后台管理账号登录</p>
          </header>

          <form class="form" @submit.prevent="handleSubmit">
            <div class="field">
              <label for="login-username">账号</label>
              <n-input
                id="login-username"
                v-model:value="username"
                size="large"
                placeholder="登录名"
                :input-props="{ autocomplete: 'username', autocapitalize: 'off' }"
                @keyup.enter="handleSubmit"
              >
                <template #prefix>
                  <n-icon :component="PersonOutline" :size="16" />
                </template>
              </n-input>
            </div>

            <div class="field">
              <label for="login-password">密码</label>
              <n-input
                id="login-password"
                v-model:value="password"
                type="password"
                show-password-on="click"
                size="large"
                placeholder="密码"
                :input-props="{ autocomplete: 'current-password' }"
                @keyup.enter="handleSubmit"
              >
                <template #prefix>
                  <n-icon :component="LockClosedOutline" :size="16" />
                </template>
              </n-input>
            </div>

            <div class="row">
              <n-checkbox v-model:checked="remember" size="small">记住账号</n-checkbox>
            </div>

            <transition name="fade">
              <div v-if="failure" class="error" :class="{ soft: failure.unreachable }">
                <n-icon :component="TrendingUpOutline" :size="14" class="err-icon" />
                <span>{{ failure.message }}</span>
              </div>
            </transition>

            <n-button
              type="primary"
              size="large"
              block
              strong
              :loading="submitting"
              :disabled="!canSubmit"
              attr-type="submit"
              class="submit"
            >
              进入系统
              <template #icon>
                <n-icon :component="ArrowForwardOutline" />
              </template>
            </n-button>
          </form>

          <!-- 演示凭据：只在演示态出现，点一下直接填表 -->
          <div v-if="USE_MOCK" class="demo">
            <div class="demo-head">
              <span class="demo-badge">演示态</span>
              <span class="demo-note">账号密码都是编的，点一下直接填入</span>
            </div>
            <div class="demo-list">
              <button
                v-for="h in DEMO_LOGIN_HINTS"
                :key="h.username"
                type="button"
                class="demo-item"
                @click="useDemo(h)"
              >
                <span class="demo-user">{{ h.username }}</span>
                <span class="demo-pwd">{{ h.password }}</span>
                <span class="demo-role">{{ h.label }} · {{ h.desc }}</span>
              </button>
            </div>
            <p class="demo-foot">
              另有 <code>liuyi</code>（已停用）与 <code>test_tmp</code>（已锁定），
              可用来查看失败提示。
            </p>
          </div>
        </div>

      </section>
    </div>
  </div>
</template>

<style scoped>
/*
 * 整页用一张自己的底色，不复用 --bg-body：
 * 登录页要独立的气质，且不随业务页滚动。
 */
.login {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: var(--bg-body);
}

/* ------------------------------- 背景层 ------------------------------- */

.bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

/* 细网格：条件单/策略的「精度」意象，靠 mask 让它向边缘淡出 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, color-mix(in srgb, var(--text-primary) 7%, transparent) 1px, transparent 1px),
    linear-gradient(to bottom, color-mix(in srgb, var(--text-primary) 7%, transparent) 1px, transparent 1px);
  background-size: 46px 46px;
  -webkit-mask-image: radial-gradient(ellipse 90% 70% at 30% 40%, #000 10%, transparent 75%);
  mask-image: radial-gradient(ellipse 90% 70% at 30% 40%, #000 10%, transparent 75%);
}

.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
}

.aurora-1 {
  top: -18%;
  left: -8%;
  width: 46rem;
  height: 46rem;
  background: radial-gradient(circle, #3b6ff5, transparent 62%);
}

.aurora-2 {
  bottom: -26%;
  left: 12%;
  width: 40rem;
  height: 40rem;
  background: radial-gradient(circle, #7c5cf0, transparent 64%);
  opacity: 0.4;
}

.aurora-3 {
  top: 8%;
  right: -14%;
  width: 38rem;
  height: 38rem;
  background: radial-gradient(circle, #0d9c9c, transparent 66%);
  opacity: 0.32;
}

html.dark .aurora {
  opacity: 0.3;
}

/* ------------------------------- 布局 ------------------------------- */

.shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 0;
  min-height: 100vh;
  max-width: 1440px;
  margin: 0 auto;
}

/* ------------------------------- 左：品牌 ------------------------------- */

.brand {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 52px 60px 40px 56px;
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 13px;
}

.logo-mark {
  display: grid;
  flex: none;
  place-items: center;
  width: 42px;
  height: 42px;
  color: #fff;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.5px;
  background: linear-gradient(135deg, #3b6ff5, #7c5cf0);
  border-radius: 12px;
  box-shadow: 0 6px 18px rgba(59, 111, 245, 0.34);
}

.logo-title {
  font-size: 16px;
  font-weight: 650;
  line-height: 1.2;
  color: var(--text-primary);
}

.logo-sub {
  margin-top: 2px;
  font-size: 9.5px;
  letter-spacing: 2.1px;
  color: var(--text-muted);
}

.brand-main {
  max-width: 30rem;
}

.headline {
  margin: 0;
  font-size: 42px;
  font-weight: 300;
  line-height: 1.28;
  letter-spacing: -0.5px;
  color: var(--text-primary);
}

/* 只强调一处，其余保持素色 —— 少即是多 */
.hl {
  font-weight: 650;
  background: linear-gradient(96deg, #3b6ff5, #7c5cf0 62%, #0d9c9c);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: #3b6ff5;
}

.sub {
  margin: 16px 0 0;
  font-size: 14px;
  line-height: 1.75;
  color: var(--text-secondary);
}

/* 条件单走势示意 */
.spark {
  position: relative;
  height: 132px;
  margin: 30px 0 4px;
  color: #3b6ff5;
}

html.dark .spark {
  color: #6d95ff;
}

.spark svg {
  width: 100%;
  height: 100%;
  overflow: visible;
}

.spark-line {
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 620;
  stroke-dashoffset: 620;
  animation: draw 2.1s cubic-bezier(0.22, 0.61, 0.36, 1) 0.25s forwards;
}

.spark-area {
  fill: url(#areaFill);
  opacity: 0;
  animation: fadeIn 1s ease 1.5s forwards;
}

/* 阈值线用虚线：一眼区别于走势 */
.spark-threshold {
  stroke: currentColor;
  stroke-width: 1.2;
  stroke-dasharray: 5 5;
  opacity: 0.45;
}

.spark-trigger {
  stroke: currentColor;
  stroke-width: 1;
  stroke-dasharray: 3 4;
  opacity: 0.3;
}

.spark-dot {
  fill: currentColor;
}

.spark-dot-halo {
  fill: currentColor;
  opacity: 0.22;
  animation: pulse 2.4s ease-in-out 2.2s infinite;
}

.spark-tag {
  position: absolute;
  top: 4px;
  left: 288px;
  padding: 3px 9px;
  font-size: 11px;
  white-space: nowrap;
  color: #fff;
  background: linear-gradient(135deg, #3b6ff5, #7c5cf0);
  border-radius: 999px;
  box-shadow: 0 4px 12px rgba(59, 111, 245, 0.3);
  opacity: 0;
  animation: fadeIn 0.6s ease 2.4s forwards;
}

.highlights {
  display: grid;
  gap: 13px;
  margin: 26px 0 0;
  padding: 0;
  list-style: none;
}

.highlights li {
  display: flex;
  align-items: flex-start;
  gap: 11px;
}

.hl-icon {
  margin-top: 2px;
  color: var(--text-muted);
}

.hl-title {
  font-size: 13px;
  font-weight: 550;
  color: var(--text-primary);
}

.hl-desc {
  margin-top: 1px;
  font-size: 12px;
  color: var(--text-muted);
}

.brand-foot {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  color: var(--text-muted);
}

/* ------------------------------- 右：表单 ------------------------------- */

.panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 56px 40px 20px;
}

.card {
  width: 100%;
  max-width: 384px;
  padding: 34px 32px 28px;
  background: color-mix(in srgb, var(--bg-surface) 88%, transparent);
  border: 1px solid var(--border);
  border-radius: 18px;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 24px 60px -18px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(18px) saturate(140%);
}

.card-head h2 {
  margin: 0;
  font-size: 23px;
  font-weight: 620;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.card-head p {
  margin: 7px 0 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

.form {
  margin-top: 26px;
}

.field + .field {
  margin-top: 16px;
}

.field label {
  display: block;
  margin-bottom: 7px;
  font-size: 11.5px;
  font-weight: 550;
  letter-spacing: 0.4px;
  color: var(--text-secondary);
}

/*
 * 输入框内边距与圆角：naive-ui 默认偏紧凑，
 * 登录页需要更大的触达面积才显从容。选择器带两层类，
 * 否则会被 naive-ui 自己的 `.n-input .n-input__input-el` 静默盖掉。
 */
.card :deep(.n-input) {
  border-radius: 10px;
}

.card :deep(.n-input .n-input__border),
.card :deep(.n-input .n-input__state-border) {
  border-radius: 10px;
}

.card :deep(.n-input .n-input__input-el) {
  height: 46px;
  font-size: 14px;
}

.card :deep(.n-input .n-input__prefix) {
  margin-right: 2px;
  color: var(--text-muted);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 15px 0 0;
}

.error {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 14px;
  padding: 9px 11px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 9%, transparent);
  border-radius: 9px;
  border-left: 2.5px solid var(--danger);
}

/* 「接口未就绪」不是账号错误，用中性色，避免误导用户以为密码错了 */
.error.soft {
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 10%, transparent);
  border-left-color: var(--warning);
}

.err-icon {
  flex: none;
  margin-top: 2px;
}

.submit {
  margin-top: 20px;
  border-radius: 10px;
}

/* ------------------------------- 演示凭据 ------------------------------- */

.demo {
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px dashed var(--border);
}

.demo-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demo-badge {
  padding: 1.5px 7px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--warning);
  background: color-mix(in srgb, var(--warning) 13%, transparent);
  border-radius: 999px;
}

.demo-note {
  font-size: 11.5px;
  color: var(--text-muted);
}

.demo-list {
  display: grid;
  gap: 6px;
  margin-top: 11px;
}

.demo-item {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 10px;
  align-items: baseline;
  padding: 8px 11px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--bg-body);
  border: 1px solid transparent;
  border-radius: 9px;
  transition: all 0.15s ease;
}

.demo-item:hover {
  border-color: color-mix(in srgb, var(--brand) 40%, transparent);
  transform: translateY(-1px);
}

.demo-user {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-primary);
}

.demo-pwd {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  color: var(--text-secondary);
}

.demo-role {
  grid-column: 1 / -1;
  font-size: 11px;
  color: var(--text-muted);
}

.demo-foot {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--text-muted);
}

.demo-foot code,
.panel-foot code {
  padding: 1px 5px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  background: var(--bg-code);
  border-radius: 4px;
}

.panel-foot {
  margin: 18px 0 0;
  font-size: 11.5px;
  text-align: center;
  color: var(--text-muted);
}

/* ------------------------------- 动效 ------------------------------- */

@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}

@keyframes fadeIn {
  to {
    opacity: 1;
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.22;
    transform: scale(1);
  }
  50% {
    opacity: 0.4;
    transform: scale(1.55);
  }
}

.spark-dot-halo {
  transform-origin: 286px 46px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 尊重「减少动态效果」偏好：装饰动画全部关掉 */
@media (prefers-reduced-motion: reduce) {
  .spark-line,
  .spark-area,
  .spark-tag,
  .spark-dot-halo {
    animation: none;
    opacity: 1;
    stroke-dashoffset: 0;
  }
}

/* ------------------------------- 响应式 ------------------------------- */

@media (max-width: 1024px) {
  .shell {
    grid-template-columns: 1fr;
  }

  .brand {
    padding: 40px 32px 8px;
  }

  .headline {
    font-size: 34px;
  }

  .spark,
  .highlights {
    display: none;
  }

  .panel {
    padding: 28px 32px 48px;
  }
}

@media (max-width: 560px) {
  .brand {
    padding: 28px 20px 4px;
  }

  .headline {
    font-size: 27px;
  }

  .panel {
    padding: 20px 20px 40px;
  }

  .card {
    padding: 26px 20px 22px;
  }
}
</style>
