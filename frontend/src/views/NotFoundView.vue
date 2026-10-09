<script setup lang="ts">
import { NButton, NResult } from 'naive-ui'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <n-result status="404" title="页面不存在" description="请检查地址是否正确，或从左侧菜单进入。">
    <template #footer>
      <n-button @click="router.push('/')">返回首页</n-button>
    </template>
  </n-result>
</template>
