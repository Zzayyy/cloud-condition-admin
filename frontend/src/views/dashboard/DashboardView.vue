<script setup lang="ts">
import { h } from 'vue'
import {
  NCard,
  NDataTable,
  NGi,
  NGrid,
  NIcon,
  NProgress,
  NTag,
  NTimeline,
  NTimelineItem,
  type DataTableColumns,
} from 'naive-ui'
import {
  CashOutline,
  CloudOutline,
  LayersOutline,
  PulseOutline,
  ServerOutline,
  SpeedometerOutline,
  SwapHorizontalOutline,
  TimeOutline,
} from '@vicons/ionicons5'

import { PROTOCOLS, type ProtocolDef } from '@/api/domain'

/**
 * 总览页。
 *
 * 目前展示的是**现场实测快照**（写代码时只读探查得到），
 * 用于把系统真实规模交代清楚；后端接口就绪后替换为实时数据即可。
 */

interface StatCard {
  label: string
  value: string
  suffix: string
  icon: typeof ServerOutline
  color: string
  hint: string
}

const stats: StatCard[] = [
  {
    label: '在线委托服务器',
    value: '2',
    suffix: '个',
    icon: SwapHorizontalOutline,
    color: '#0d9c9c',
    hint: 'traderserver 键共 7 条记录，enable=true 的 3 条',
  },
  {
    label: '在线策略服务器',
    value: '1',
    suffix: '个',
    icon: LayersOutline,
    color: '#7c5cf0',
    hint: 'strategy-srv 键中 enable=true 的节点',
  },
  {
    label: '运行中条件单',
    value: '0',
    suffix: '条',
    icon: ServerOutline,
    color: '#dd7a15',
    hint: 'T_Conditional 当前 0 行（历史表同样为空）',
  },
  {
    label: '入口报文总量',
    value: '407.3',
    suffix: '万条',
    icon: PulseOutline,
    color: '#d6455e',
    hint: '137 db0 的 DataHub_req_stream，查询必须限流',
  },
]

interface SourceRow {
  name: string
  kind: string
  kindLabel: string
  scale: string
  note: string
}

const sourceRows: SourceRow[] = [
  {
    name: 'DataHub_req_stream',
    kind: 'redis',
    kindLabel: 'Redis Stream',
    scale: '407.3 万条',
    note: '委托服务器 → 数据中台入口，积压量大',
  },
  {
    name: 'DataHub_reply_stream',
    kind: 'redis',
    kindLabel: 'Redis Stream',
    scale: '3.6 万条',
    note: '数据中台回包流',
  },
  {
    name: 'DataHub_req_stream @db2',
    kind: 'redis',
    kindLabel: 'Redis Stream',
    scale: '6004 条',
    note: '破坏测试专用流，与生产流隔离',
  },
  {
    name: 'ST-<编号> / WT-<编号>',
    kind: 'redis',
    kindLabel: 'Redis Stream',
    scale: '137 侧约 50 条流',
    note: '中台 → 策略平台 / 委托服务器 下发流，消费组固定 user_group',
  },
  {
    name: 'traderserver',
    kind: 'redis',
    kindLabel: 'Redis JSON',
    scale: '7 条记录',
    note: '委托服务器节点清单，含 enable 开关与最后活跃时间',
  },
  {
    name: 'strategy-srv',
    kind: 'redis',
    kindLabel: 'Redis JSON',
    scale: '1 条记录',
    note: '策略服务器节点清单，含 usecount（承载条件单用户数）',
  },
  {
    name: 'acc-* / strategy-*',
    kind: 'redis',
    kindLabel: 'Redis JSON',
    scale: '18 个键',
    note: '账号与条件单的实时对象',
  },
  {
    name: 'T_Conditional(_his)',
    kind: 'mysql',
    kindLabel: 'MySQL',
    scale: '0 行',
    note: '条件单主表与历史表，另有 V_Conditional 视图取并集',
  },
  {
    name: 'T_UserLog',
    kind: 'mysql',
    kindLabel: 'MySQL',
    scale: '19.05 万行',
    note: '登录日志，查询必须分页',
  },
  {
    name: 'T_StrategyLog',
    kind: 'mysql',
    kindLabel: 'MySQL',
    scale: '1.66 万行',
    note: '策略运行日志',
  },
  {
    name: 'T_Order(_his) / T_Trade(_his)',
    kind: 'mysql',
    kindLabel: 'MySQL',
    scale: '自增 2644 / 979',
    note: '委托与成交数据',
  },
]

const KIND_COLOR: Record<string, string> = {
  redis: '#d6455e',
  mysql: '#0d9c9c',
}

const sourceColumns: DataTableColumns<SourceRow> = [
  {
    title: '数据来源',
    key: 'name',
    width: 220,
    render: (row) =>
      h('span', { class: 'mono', style: { fontWeight: 500 } }, row.name),
  },
  {
    title: '类型',
    key: 'kindLabel',
    width: 130,
    render: (row) =>
      h(
        NTag,
        {
          size: 'small',
          round: true,
          bordered: false,
          style: {
            color: KIND_COLOR[row.kind],
            background: `${KIND_COLOR[row.kind]}1f`,
            fontWeight: 600,
          },
        },
        { default: () => row.kindLabel },
      ),
  },
  { title: '实测规模', key: 'scale', width: 140 },
  { title: '说明', key: 'note', ellipsis: { tooltip: true } },
]

const protocolColumns: DataTableColumns<ProtocolDef> = [
  {
    title: '协议名',
    key: 'name',
    width: 120,
    render: (row) => h('span', { class: 'mono' }, row.name),
  },
  { title: '名称', key: 'title', width: 140 },
  {
    title: '分类',
    key: 'category',
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: 'small', round: true, bordered: false, type: row.category === '账号类' ? 'success' : 'info' },
        { default: () => row.category },
      ),
  },
  { title: '说明', key: 'desc', ellipsis: { tooltip: true } },
]

const milestones = [
  {
    title: '现场结构勘察',
    content: 'MySQL 10 张表、Redis 136/137 的 stream / JSON / 频道已核对',
    type: 'success' as const,
  },
  {
    title: '前端骨架与菜单',
    content: 'Vue3 + Vite + TS + Naive UI，7 个一级菜单 / 20 个页面',
    type: 'success' as const,
  },
  {
    title: '后端接口设计',
    content: '按资源定义 REST 接口与统一响应结构',
    type: 'info' as const,
  },
  {
    title: '页面逐个实现',
    content: '优先：策略服务器 → 委托服务器 → 消息总线',
    type: 'default' as const,
  },
]

/** 各模块完成度（占位页数量统计用，这里先给出手工进度） */
const progress = [
  { label: '前端骨架', value: 100, color: '#2f9e5f' },
  { label: '菜单与路由', value: 100, color: '#2f9e5f' },
  { label: '后端接口', value: 0, color: '#d6455e' },
  { label: '业务页面', value: 4, color: '#dd7a15' },
]
</script>

<template>
  <div>
    <!-- 指标卡片 -->
    <n-grid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
      <n-gi v-for="stat in stats" :key="stat.label">
        <div class="stat-card" :style="{ '--c': stat.color }">
          <div class="stat-icon">
            <n-icon :component="stat.icon" :size="20" />
          </div>
          <div class="stat-body">
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-value">
              {{ stat.value }}<span class="stat-suffix">{{ stat.suffix }}</span>
            </div>
            <div class="stat-hint">{{ stat.hint }}</div>
          </div>
        </div>
      </n-gi>
    </n-grid>

    <!-- 数据来源 -->
    <n-card class="page-card" size="small" :bordered="false" style="margin-top: 16px">
      <template #header>
        <span class="section-title">
          <n-icon :component="CloudOutline" color="#3b6ff5" :size="17" />
          系统数据来源与实测规模
        </span>
      </template>
      <template #header-extra>
        <n-tag size="small" type="warning" round :bordered="false">快照数据 · 待接后端</n-tag>
      </template>
      <n-data-table
        :columns="sourceColumns"
        :data="sourceRows"
        :bordered="false"
        :single-line="false"
        size="small"
        :pagination="{ pageSize: 6 }"
      />
    </n-card>

    <n-grid cols="1 m:2" :x-gap="16" responsive="screen" item-responsive>
      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="CashOutline" color="#2f9e5f" :size="17" />
              客户端协议清单
            </span>
          </template>
          <n-data-table
            :columns="protocolColumns"
            :data="PROTOCOLS"
            :bordered="false"
            :single-line="false"
            size="small"
            :pagination="false"
          />
        </n-card>
      </n-gi>

      <n-gi>
        <n-card class="page-card" size="small" :bordered="false">
          <template #header>
            <span class="section-title">
              <n-icon :component="TimeOutline" color="#7c5cf0" :size="17" />
              建设进度
            </span>
          </template>
          <n-timeline>
            <n-timeline-item
              v-for="item in milestones"
              :key="item.title"
              :title="item.title"
              :content="item.content"
              :type="item.type"
            />
          </n-timeline>

          <div class="progress-block">
            <div v-for="p in progress" :key="p.label" class="progress-row">
              <span class="progress-label">{{ p.label }}</span>
              <n-progress
                type="line"
                :percentage="p.value"
                :color="p.color"
                :height="6"
                :show-indicator="false"
                :border-radius="3"
              />
              <span class="progress-value" :style="{ color: p.color }">{{ p.value }}%</span>
            </div>
          </div>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- 风险提示 -->
    <n-card class="page-card risk-card" size="small" :bordered="false">
      <template #header>
        <span class="section-title">
          <n-icon :component="SpeedometerOutline" color="#d6455e" :size="17" />
          接入前必读：生产环境硬约束
        </span>
      </template>
      <ul class="risk-list">
        <li>
          <b>137 db0</b> 的 <code>DataHub_req_stream</code> 有 <b>407 万条</b>，列表接口必须强制分页 + 条数上限，禁止全量 <code>XRANGE</code>。
        </li>
        <li>
          <b>T_UserLog</b> 19 万行、<b>T_StrategyLog</b> 1.6 万行，查询必须走分页与索引。
        </li>
        <li>Redis 键扫描一律用 <code>SCAN</code>，禁止 <code>KEYS *</code>。</li>
        <li>
          破坏测试写的是 <b>137 db2</b> 的 <code>DataHub_req_stream</code>（与生产流隔离），不要写到 db0/db1。
        </li>
        <li>高危操作（删键、删流、破坏测试、清理数据、改连接配置）需二次确认 + 写审计。</li>
      </ul>
    </n-card>
  </div>
</template>

<style scoped>
/* --------------------------------- 指标卡 -------------------------------- */

.stat-card {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 18px 20px;
  overflow: hidden;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    border-color 0.18s ease;
}

.stat-card::after {
  content: '';
  position: absolute;
  inset: auto -30px -40px auto;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: var(--c);
  opacity: var(--stat-glow);
  pointer-events: none;
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--c) 40%, var(--border));
  box-shadow: var(--shadow-md);
}

.stat-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 11px;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 13%, transparent);
}

.stat-body {
  min-width: 0;
}

.stat-label {
  font-size: 12.5px;
  color: var(--text-muted);
}

.stat-value {
  margin-top: 3px;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.15;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.stat-suffix {
  margin-left: 3px;
  font-size: 13px;
  font-weight: 400;
  color: var(--text-muted);
}

.stat-hint {
  margin-top: 5px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-muted);
}

/* --------------------------------- 进度条 -------------------------------- */

.progress-block {
  display: grid;
  gap: 12px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
}

.progress-row {
  display: grid;
  grid-template-columns: 76px 1fr 44px;
  gap: 12px;
  align-items: center;
}

.progress-label {
  font-size: 12.5px;
  color: var(--text-secondary);
}

.progress-value {
  font-size: 12.5px;
  font-weight: 600;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* --------------------------------- 风险卡 -------------------------------- */

.risk-card {
  border-left: 3px solid var(--danger) !important;
}

.risk-list {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 2;
  color: var(--text-secondary);
}

.risk-list b {
  color: var(--danger);
  font-weight: 600;
}

.risk-list code {
  padding: 1.5px 6px;
  margin: 0 1px;
  border-radius: 5px;
  background: var(--bg-code);
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  color: var(--text-primary);
}
</style>
