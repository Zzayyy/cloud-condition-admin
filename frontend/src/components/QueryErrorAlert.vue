<script setup lang="ts">
import { NAlert, NButton, NIcon } from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'

/** 接口失败提示。参数明确、可重试，不用猜为什么页面是空的。 */
defineProps<{
  message?: string
  loading?: boolean
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <n-alert class="err-alert" type="error" :bordered="false">
    <template #header>数据加载失败</template>
    <div class="err-body">
      <span>{{ message || '请求后端接口失败' }}</span>
      <n-button size="tiny" :loading="loading" @click="$emit('retry')">
        <template #icon>
          <n-icon :component="RefreshOutline" />
        </template>
        重试
      </n-button>
    </div>
  </n-alert>
</template>

<style scoped>
.err-alert {
  margin-bottom: 16px;
}

.err-body {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
