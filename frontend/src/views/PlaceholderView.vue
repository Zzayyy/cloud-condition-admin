<script setup lang="ts">
import PagePlaceholder from '@/components/PagePlaceholder.vue'

/**
 * 通用占位页：菜单里没有指定 `view` 的节点都会落到这里。
 * 组件自身会从路由 meta（由菜单树注入）读取数据来源与待办清单。
 */
</script>

<template>
  <PagePlaceholder />
</template>
