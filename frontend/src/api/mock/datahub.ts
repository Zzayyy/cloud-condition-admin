/**
 * 数据中台管理 —— 演示数据（Mock）。
 *
 * **这份数据的结构与数量照现场只读探查结果来**（见下方每个常量的注释），
 * 但请注意：
 *  - 流长度 / 消费组 / 频道订阅数是**快照**，会随后端变化；
 *  - 报文样本（task 字段）取自现场真实条目，用于让「报文查看」看起来是真的；
 *  - 后端接通后本文件可整个删掉。
 */
import type {
  DatahubChannelScan,
  DatahubNode,
  DatahubNodePage,
  RedisPubSubChannel,
  RedisStreamEntry,
  RedisStreamInfo,
  StreamRole,
} from '../domain'
import type { PageQuery, PageResult } from '../types'
import { paginate, seededRandom, type Rand } from './_shared'

const rand: Rand = seededRandom(20261011)

/* ------------------------------ 实例与节点 ------------------------------ */

/**
 * 现场实测（快照，2026-10-14）：
 *   192.168.1.136  version 8.6.2  uptime 91.7 天  clients 14  mem 266.59M
 *                  db0: unique_id=217，10 条 stream（242194 条目），14 个频道
 *                  db1: unique_id=56，10 条 stream（40484 条目）
 *   192.168.1.137  version 8.6.2  uptime 61.9 天  clients 2   mem 4.52G
 *                  db0: **无 unique_id**，116 条 stream（15174665 条目），0 个频道
 *                  db1: unique_id=4，10 条 stream（26 条目）
 *                  db2: **无 unique_id**，3 条 stream（6004 条目，破坏测试流）
 *
 * ⚠️ 这里刻意**不含** mac / lastActiveTime：Redis 侧没有这两个数据源
 * （心跳报文只有 `{"level":2,"role":1}`）。演示态若编出这两个字段，
 * 页面就会以为真实态也该有，上线后变成两列空白。
 *
 * ⚠️ `online` 用 `null`（未知）而不是全 `true`：真实态刚启动时就是未知，
 * 演示态全给 true 会让「未知」这条分支从未被走通过。
 */
export const DATAHUB_NODES: DatahubNode[] = [
  {
    id: '192.168.1.136',
    ip: '192.168.1.136',
    port: '6379',
    online: true,
    state: 'ONLINE',
    lastBeatAgeSec: 3,
    beatCount: 1284,
    subscribed: true,
    subscribeError: null,
    dbs: [
      { db: 0, keys: 13, uniqueId: 217, entryTotal: 242194, streamTotal: 10 },
      { db: 1, keys: 29, uniqueId: 56, entryTotal: 40484, streamTotal: 10 },
    ],
    dbKeysTotal: 42,
    entryTotal: 282678,
    streamTotal: 20,
    channels: 14,
  },
  {
    id: '192.168.1.137',
    ip: '192.168.1.137',
    port: '6379',
    // 137 的 Redis 上没有任何中台心跳（实测订阅 6 秒 0 条）—— 如实显示离线
    online: false,
    state: 'OFFLINE',
    lastBeatAgeSec: null,
    beatCount: 0,
    subscribed: true,
    subscribeError: null,
    dbs: [
      { db: 0, keys: 117, uniqueId: null, entryTotal: 15174665, streamTotal: 116 },
      { db: 1, keys: 15, uniqueId: 4, entryTotal: 26, streamTotal: 10 },
      { db: 2, keys: 3, uniqueId: null, entryTotal: 6004, streamTotal: 3 },
    ],
    dbKeysTotal: 135,
    entryTotal: 15180695,
    streamTotal: 129,
    channels: 0,
  },
]

/* --------------------------------- 频道 --------------------------------- */

/**
 * 现场实测：136 上 14 个频道，**每个订阅者都是 1**；137 上 0 个频道。
 * 注意频道名全部带 `_1` 后缀 —— 这是现场真中台订阅的那一套。
 */
const CHANNEL_NAMES: Array<[string, RedisPubSubChannel['group']]> = [
  ['tradeserver_online_1', 'trade'],
  ['tradeserver_beat_1', 'trade'],
  ['tradeserver_offline_1', 'trade'],
  ['strategyserver_online_1', 'strategy'],
  ['strategyserver_beat_1', 'strategy'],
  ['strategyserver_offline_1', 'strategy'],
  ['datahub_online_1', 'datahub'],
  ['datahub_beat_1', 'datahub'],
  ['datahub_offline_1', 'datahub'],
  ['datahub_select_1', 'datahub'],
  ['datahub_oncall_1', 'datahub'],
  ['WT-415-2cea7fd9d5c0cloudcond-api', 'directed'],
  ['WT-226-2cea7fd9d5c0Test', 'directed'],
  ['WT-64-20474789c674Test-1', 'directed'],
]

export const DATAHUB_CHANNELS: RedisPubSubChannel[] = [
  ...CHANNEL_NAMES.map(([name, group]) => ({
    name,
    subscribers: 1,
    group,
    instance: '192.168.1.136',
  })),
]

/* --------------------------------- 流 --------------------------------- */

function roleOf(key: string): StreamRole {
  if (key === 'DataHub_req_stream') return 'ingress'
  if (key === 'DataHub_reply_stream') return 'reply'
  if (key.endsWith('-reply')) return 'ack'
  if (/^(ST|WT)-/.test(key)) return 'downstream'
  return 'other'
}

interface RawStream {
  key: string
  instance: string
  db: number
  length: number
  consumers: number
  pending: number
  lag: number
  /** 有无消费组 —— 现场 137 的入口流就完全没有消费组 */
  hasGroup: boolean
}

/** 照现场快照逐条录入（137 db0 只列前 12 条，其余归入「其他」） */
const RAW_STREAMS: RawStream[] = [
  // ---- 192.168.1.136 ----
  { key: 'DataHub_reply_stream', instance: '192.168.1.136', db: 0, length: 52114, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-0', instance: '192.168.1.136', db: 0, length: 9554, consumers: 1, pending: 595, lag: 0, hasGroup: true },
  { key: 'DataHub_req_stream', instance: '192.168.1.136', db: 0, length: 6057, consumers: 1, pending: 11, lag: 0, hasGroup: true },
  { key: 'ST-0-reply', instance: '192.168.1.136', db: 0, length: 1247, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'WT-0', instance: '192.168.1.136', db: 0, length: 106, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'WT-0-reply', instance: '192.168.1.136', db: 0, length: 89, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'WT-1-reply', instance: '192.168.1.136', db: 0, length: 86, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'WT-1', instance: '192.168.1.136', db: 0, length: 4, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-1-reply', instance: '192.168.1.136', db: 0, length: 0, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-1', instance: '192.168.1.136', db: 0, length: 0, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'DataHub_req_stream', instance: '192.168.1.136', db: 1, length: 20240, consumers: 1, pending: 2, lag: 0, hasGroup: true },
  { key: 'WT-1-reply', instance: '192.168.1.136', db: 1, length: 20137, consumers: 1, pending: 162, lag: 0, hasGroup: true },
  { key: 'WT-3-reply', instance: '192.168.1.136', db: 1, length: 99, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'WT-0-reply', instance: '192.168.1.136', db: 1, length: 2, consumers: 1, pending: 0, lag: 0, hasGroup: true },

  // ---- 192.168.1.137 ----
  // 入口流 407 万条且 **没有消费组** —— 这是最需要注意的一条
  { key: 'DataHub_req_stream', instance: '192.168.1.137', db: 0, length: 4073271, consumers: 0, pending: 0, lag: 0, hasGroup: false },
  { key: 'ST-20', instance: '192.168.1.137', db: 0, length: 320800, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'DataHub_reply_stream', instance: '192.168.1.137', db: 0, length: 35832, consumers: 0, pending: 0, lag: 0, hasGroup: false },
  { key: 'ST-23', instance: '192.168.1.137', db: 0, length: 13200, consumers: 1, pending: 0, lag: 1200, hasGroup: true },
  { key: 'ST-18', instance: '192.168.1.137', db: 0, length: 13100, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-22', instance: '192.168.1.137', db: 0, length: 13000, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-24', instance: '192.168.1.137', db: 0, length: 11500, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-16', instance: '192.168.1.137', db: 0, length: 10501, consumers: 1, pending: 0, lag: 500, hasGroup: true },
  { key: 'ST-15', instance: '192.168.1.137', db: 0, length: 10500, consumers: 1, pending: 0, lag: 10500, hasGroup: true },
  { key: 'ST-17', instance: '192.168.1.137', db: 0, length: 10000, consumers: 1, pending: 0, lag: 0, hasGroup: true },
  { key: 'ST-59', instance: '192.168.1.137', db: 0, length: 7600, consumers: 1, pending: 0, lag: 1500, hasGroup: true },
  { key: 'ST-35', instance: '192.168.1.137', db: 0, length: 6000, consumers: 1, pending: 0, lag: 3200, hasGroup: true },
  { key: 'DataHub_req_stream', instance: '192.168.1.137', db: 2, length: 6004, consumers: 0, pending: 0, lag: 0, hasGroup: false },
]

function toStreamInfo(raw: RawStream): RedisStreamInfo {
  return {
    key: raw.key,
    length: raw.length,
    instance: raw.instance,
    db: raw.db,
    role: roleOf(raw.key),
    groups: raw.hasGroup
      ? [
          {
            name: 'user_group',
            consumers: raw.consumers,
            pending: raw.pending,
            'last-delivered-id': '0-0',
            lag: raw.lag,
          },
        ]
      : [],
  }
}

const STREAMS: RedisStreamInfo[] = RAW_STREAMS.map(toStreamInfo)

/* ------------------------------ 报文样本 ------------------------------ */

/**
 * 现场真实条目（原样抄自只读探查），用于「报文查看」有真东西可看。
 * 这些是**真实报文**，不是编的。
 */
export const SAMPLE_ENTRIES: Array<{ stream: string; instance: string; db: number; entry: RedisStreamEntry }> = [
  {
    stream: 'DataHub_req_stream',
    instance: '192.168.1.136',
    db: 0,
    entry: {
      id: '1790731930263-4',
      fields: {
        request_id: 'ST_192.168.1.136_1790731930_4',
        server_id: '0',
        server_type: 'ST',
        reply_req_stream: 'ST-0',
        reply_reply_stream: 'ST-0-reply',
        task: '{"createTrade":{"Unique_Account":"010100011300_7_6","Id":4918528,"StrategyID":"20260930000166","OrderNo":"20433","TradeNo":"0600000015538201","Market":"深圳","Code":"90007461","Volumn":1,"Price":"0.5435","TradeDateTime":"2026-09-30 09:31:47"}}',
      },
    },
  },
  {
    stream: 'DataHub_req_stream',
    instance: '192.168.1.137',
    db: 0,
    entry: {
      id: '1791526745818-0',
      fields: {
        request_id: 'ST_192.168.1.136_1791526745_578',
        server_id: '60',
        server_type: 'ST',
        reply_req_stream: 'ST-60',
        reply_reply_stream: 'ST-60-reply',
        task: '{"modifyOrder":{"Unique_Account":"010100011399_7_6","OrderNo":"10007922","Status":8,"StatusDis":"已成","Market":"深圳","CodeName":"","OrderTime":"2026-10-09 14:18:52"}}',
      },
    },
  },
  {
    stream: 'DataHub_reply_stream',
    instance: '192.168.1.136',
    db: 0,
    entry: {
      id: '1790732167161-0',
      fields: {
        request_id: 'datahub_1790732166_0',
        task: '{"Account":{"AccountType":7,"AccAtt":6,"FAccount":"010100011300"},"Errmsg":"insert success","ErrID":0}',
      },
    },
  },
  {
    stream: 'DataHub_reply_stream',
    instance: '192.168.1.137',
    db: 0,
    entry: {
      id: '1791526746068-2',
      fields: {
        request_id: 'STTEST_1791526745829_63',
        task: '{"Ref":"20261009000063","Errmsg":"remove success","ErrID":0}',
      },
    },
  },
  {
    stream: 'ST-0',
    instance: '192.168.1.136',
    db: 0,
    entry: {
      id: '1790732166162-0',
      fields: {
        request_id: 'datahub_1790732166_0',
        task: '{"MsgType":18,"Account":{"Account":{"FAccount":"010100011300","AccountType":7,"AccAtt":6},"ClientName":"张国昌","Pwd":"VIuCwEUE1YEWsRUYYibOq/MUA0+1AHd7+CTpfS48O8UuPoNmB754KhRoXu+a2CqdHnaCtoRd2V37YrF3P3p+6Q==","TradeAccount":"010100011300","LoginIP":"","LoginMAC":"","LoginOS":"","UniqueAccount":"010100011300_7_6","BranchNo":"123456","Shareholders":[{"SAccount":"A442523077","ExchangeNum":1},{"SAccount":"0199908393","ExchangeNum":2}]}}',
      },
    },
  },
  {
    stream: 'ST-51',
    instance: '192.168.1.137',
    db: 0,
    entry: {
      id: '1791512344925-0',
      fields: {
        request_id: 'STTEST_1791512344893_100',
        task: '{"create":{"Account":{"Model":0,"AccountType":7,"AccAtt":6,"FAccount":"010100011400"},"CondType":1,"CondName":"name100","CondDesc":"stress11400","ValidDate":"2026-11-08","UniqueAccount":"010100011400_7_6","Ref":"20261009000100","Validity":0,"Mode":1,"RunCount":0,"Status":0,"Removed":false}}',
      },
    },
  },
]

/* ----------------------------- 对外接口 ----------------------------- */

export const mockDatahubApi = {
  /**
   * 实例列表 —— 返回结构与真实接口一致（含 `meta`）。
   *
   * 演示态若少这一块，页面会走进一条与真实态不同的分支，
   * 等于「未知/在线」这类判定从未在演示态验证过。
   */
  listNodes(query: PageQuery = {}): DatahubNodePage {
    const page = paginate(
      DATAHUB_NODES,
      query,
      (row, kw) => row.ip.includes(kw),
      { id: (r) => r.id, dbKeysTotal: (r) => r.dbKeysTotal },
    )
    const onlineCount = DATAHUB_NODES.filter((n) => n.online === true).length
    const offlineCount = DATAHUB_NODES.filter((n) => n.online === false).length
    const unknownCount = DATAHUB_NODES.length - onlineCount - offlineCount
    return {
      ...page,
      meta: {
        total: DATAHUB_NODES.length,
        onlineCount,
        offlineCount,
        unknownCount,
        heartbeatEnabled: true,
        heartbeatChannels: ['datahub_beat', 'datahub_beat_1'],
        offlineAfterSec: 30,
        observedInstances: DATAHUB_NODES.filter((n) => n.subscribed).length,
        redisInstances: ['192.168.1.136/db0', '192.168.1.136/db1', '192.168.1.137/db1'],
        redisUnavailable: [],
        note:
          '一行 = 一台 Redis 实例（中台在 Redis 侧没有节点清单键）。' +
          '「在线」指该实例上近期收到过中台心跳，不等于「这台机器上跑着中台」。',
      },
    }
  },

  listChannels(): DatahubChannelScan {
    return {
      channels: DATAHUB_CHANNELS,
      /*
       * 演示态也保留这个提示位：现场 137 原本 0 个频道，
       * 本后台为判定心跳订阅了 datahub_beat_1 后才"出现"一个。
       * 演示态若恒为空数组，页面这条分支就从未被验证过。
       */
      selfOnlyChannels: ['192.168.1.137 datahub_beat_1'],
    }
  },

  listStreams(query: PageQuery = {}): RedisStreamInfo[] {
    let list = STREAMS
    const instance = typeof query.instance === 'string' ? query.instance : ''
    if (instance) list = list.filter((s) => s.instance === instance)

    const db = Number(query.db)
    if (Number.isFinite(db)) list = list.filter((s) => s.db === db)

    const role = typeof query.role === 'string' ? query.role : ''
    if (role) list = list.filter((s) => s.role === role)

    const keyword = typeof query.keyword === 'string' ? query.keyword.trim() : ''
    if (keyword) list = list.filter((s) => s.key.includes(keyword))

    // 按长度倒序，最积压的排前面
    return [...list].sort((a, b) => b.length - a.length)
  },

  listStreamEntries(key: string, query: PageQuery = {}): PageResult<RedisStreamEntry> {
    const matched = SAMPLE_ENTRIES.filter((s) => s.stream === key).map((s) => s.entry)
    // 现场每条流只抄了 1~2 条真实样本，其余用同结构样本补齐，保证分页有内容
    const extra: RedisStreamEntry[] = []
    const total = Number(query.total) || 40
    for (let i = 0; i < total; i += 1) {
      extra.push({
        id: `1791526${String(745800 + i).slice(-6)}-${i % 4}`,
        fields: {
          request_id: `ST_192.168.1.136_17915267${String(40 + i).padStart(2, '0')}_${i}`,
          server_id: String(rand() > 0.5 ? 0 : 60),
          server_type: 'ST',
          reply_req_stream: `ST-${rand() > 0.5 ? 0 : 60}`,
          reply_reply_stream: `ST-${rand() > 0.5 ? 0 : 60}-reply`,
          task: JSON.stringify({
            createTrade: {
              Unique_Account: '010100011300_7_6',
              Id: 4918528 + i,
              StrategyID: `20260930000${String(i % 100).padStart(3, '0')}`,
              OrderNo: String(20000 + i),
              TradeNo: `0600000015538${String(200 + i).slice(-3)}`,
              Market: '深圳',
              Code: '90007461',
              Volumn: 1 + (i % 5),
              Price: '0.5435',
              TradeDateTime: '2026-09-30 09:31:47',
            },
          }),
        },
      })
    }
    const all = [...matched, ...extra]
    return paginate(
      all,
      query,
      (row, kw) =>
        JSON.stringify(row.fields).includes(kw) || row.id.includes(kw),
      { id: (r) => r.id },
    )
  },

  traceRequest(requestId: string): RedisStreamEntry[] {
    if (!requestId) return []
    const hit = SAMPLE_ENTRIES.filter(
      (s) => s.entry.fields.request_id === requestId || JSON.stringify(s.entry.fields).includes(requestId),
    )
    return hit.map((s) => s.entry)
  },
}

/* ------------------------------ 图表数据 ------------------------------ */

/*
 * 这里刻意**不再提供** mockInstanceLoad / mockBacklogStreams / mockGroupLessStreams
 * 这类「图表/告警专用」的 mock 导出。
 *
 * 原因：页面直接调用它们会绕过 isMock 判断 —— 生产构建（后端 500）时
 * 表格是空的、图表和告警区却仍然画着演示数据，假数据漏到了生产环境。
 * 现在这些派生数据一律由页面从 `useResource` 取到的流数据**现场推导**，
 * 没有数据就是空图/空列表。
 */

export { STREAMS, roleOf }
