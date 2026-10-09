<script setup lang="ts">
import { NAlert, NTag } from 'naive-ui'

/**
 * 演示数据提示条。
 *
 * 后端未接通时，页面会回退到演示数据。这个条子把「现在是假数据、
 * 以及为什么是假数据」明确说出来，避免被误认为已接通后端。
 * 接口一通，组件就不再渲染。
 */
defineProps<{
  /** 回退原因，如「网络错误：Network Error」 */
  reason?: string
}>()
</script>

<template>
  <n-alert class="mock-alert" type="warning" :bordered="false" size="small">
    <template #header>
      <span class="mock-head">
        当前为演示数据
        <n-tag size="tiny" round :bordered="false" type="warning">
          {{ reason || '后端接口尚未实现' }}
        </n-tag>
      </span>
    </template>
    页面逻辑与交互是完整的，数据是编的（结构照现场真实表/键设计）。
    后端接口就绪后本提示会自动消失。
  </n-alert>
</template>

<style scoped>
.mock-alert {
  margin-bottom: 16px;
}

.mock-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
