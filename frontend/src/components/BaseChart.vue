<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch, onMounted } from 'vue'
import { NEmpty } from 'naive-ui'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

import { useAppStore } from '@/stores/app'

// 按需注册，避免把整个 ECharts 打进包里
echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

/**
 * ECharts 封装。
 * 负责实例生命周期、容器尺寸自适应、以及跟随亮暗主题重绘。
 */
const props = withDefaults(
  defineProps<{
    option: EChartsOption
    height?: number | string
    /** 无数据时展示的文案 */
    emptyText?: string
  }>(),
  { height: 280, emptyText: '暂无数据' },
)

const appStore = useAppStore()
const container = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let observer: ResizeObserver | undefined

const heightStyle = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height,
)

/** 按当前主题给坐标轴/文字上色，否则暗色下文字会看不见 */
function themedOption(): EChartsOption {
  const dark = appStore.isDark
  const axisColor = dark ? 'rgba(255,255,255,0.45)' : 'rgba(15,23,42,0.45)'
  const splitColor = dark ? 'rgba(255,255,255,0.09)' : 'rgba(15,23,42,0.08)'
  const textColor = dark ? '#e8ecf4' : '#1a2233'
  const base = props.option

  return {
    textStyle: { color: textColor },
    tooltip: {
      backgroundColor: dark ? '#1d2637' : '#ffffff',
      borderColor: splitColor,
      textStyle: { color: textColor, fontSize: 12 },
      ...(typeof base.tooltip === 'object' ? base.tooltip : {}),
    },
    ...base,
    xAxis: Array.isArray(base.xAxis)
      ? base.xAxis.map((a) => ({
          axisLine: { lineStyle: { color: splitColor } },
          axisLabel: { color: axisColor },
          splitLine: { lineStyle: { color: splitColor } },
          ...a,
        }))
      : base.xAxis
        ? {
            axisLine: { lineStyle: { color: splitColor } },
            axisLabel: { color: axisColor },
            splitLine: { lineStyle: { color: splitColor } },
            ...base.xAxis,
          }
        : undefined,
    yAxis: Array.isArray(base.yAxis)
      ? base.yAxis.map((a) => ({
          axisLine: { lineStyle: { color: splitColor } },
          axisLabel: { color: axisColor },
          splitLine: { lineStyle: { color: splitColor } },
          ...a,
        }))
      : base.yAxis
        ? {
            axisLine: { lineStyle: { color: splitColor } },
            axisLabel: { color: axisColor },
            splitLine: { lineStyle: { color: splitColor } },
            ...base.yAxis,
          }
        : undefined,
  }
}

function hasData(): boolean {
  const s = props.option.series
  if (!s) return false
  const list = Array.isArray(s) ? s : [s]
  return list.some((item) => {
    const d = (item as { data?: unknown[] }).data
    return Array.isArray(d) && d.length > 0
  })
}

function render() {
  if (!container.value) return
  if (!chart.value) {
    chart.value = echarts.init(container.value)
  }
  chart.value.setOption(themedOption(), true)
  chart.value.resize()
}

onMounted(() => {
  render()
  if (container.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(container.value)
  }
})

watch(() => props.option, render, { deep: true })
watch(() => appStore.isDark, render)

onUnmounted(() => {
  observer?.disconnect()
  chart.value?.dispose()
  chart.value = undefined
})
</script>

<template>
  <div class="chart-wrap" :style="{ height: heightStyle }">
    <div v-show="hasData()" ref="container" class="chart-canvas" />
    <div v-if="!hasData()" class="chart-empty">
      <n-empty :description="emptyText" size="small" />
    </div>
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  width: 100%;
}

.chart-canvas {
  width: 100%;
  height: 100%;
}

.chart-empty {
  display: grid;
  place-items: center;
  height: 100%;
}
</style>
