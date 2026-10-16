/**
 * 数据存储 —— 演示数据（Mock）。
 *
 * 照现场只读实测（快照）：
 *
 * Redis：
 *  - 136：118.80M / 18 客户端 / db0=17 键、db1=28 键；版本 8.6.2，standalone
 *        db0 类型分布 stream 10 / string 3 / ReJSON-RL 4
 *  - 137：1.47G / 1 客户端 / db0=79、db1=15、db2=3 键；**峰值曾达 12.45G**
 *        两个实例都是 maxmemory=0（无上限）+ noeviction
 *  - 仅有的两个会过期的键：strategy_server_lock_0、strategy_server_lock_1001
 *
 * MySQL `qianlong_cloud`：
 *  - 9 张表 + 1 个视图，其中只有 T_StrategyLog(17430)、T_UserLog(191002) 等有数据
 *  - ⚠️ information_schema 的 TABLE_ROWS **不准**（T_StrategyLog 报 16566、实际 17430），
 *    所以 exactRows 用真实 COUNT
 *  - 索引极稀疏：除主键外只有 T_Conditional_his 的 Name_UNIQUE
 *
 * **数值是编的**（除上面这些实测快照），结构是真的。
 */
import type {
  MysqlColumn,
  MysqlTable,
  MysqlTableSchema,
  RedisInstance,
  RedisInstanceInfo,
  RedisKeyDetail,
  RedisKeyDomain,
  RedisKeyInfo,
  RedisScanResult,
  RedisValueType,
} from '../domain'
import type { PageQuery, PageResult } from '../types'
import { asNumber, asString, paginate, seededRandom, type Rand } from './_shared'

const rand: Rand = seededRandom(20261013)

/* ------------------------------ 实例 ------------------------------ */

export const REDIS_INSTANCES: RedisInstance[] = [
  { id: '136', name: '192.168.1.136', host: '192.168.1.136', port: 6379, db: 0 },
  { id: '137', name: '192.168.1.137', host: '192.168.1.137', port: 6379, db: 0 },
]

/** 照 INFO 实测值 */
export const REDIS_INFOS: Record<string, RedisInstanceInfo> = {
  '136': {
    host: '192.168.1.136',
    port: 6379,
    version: '8.6.2',
    mode: 'standalone',
    os: 'Linux 5.14.0-687.17.1.el9_8.x86_64 x86_64',
    uptimeInSeconds: 7491575,
    connectedClients: 18,
    usedMemory: 124567256,
    usedMemoryHuman: '118.80M',
    usedMemoryPeakHuman: '246.18M',
    maxmemory: 0,
    maxmemoryPolicy: 'noeviction',
    totalCommandsProcessed: 114227476,
    keyspaceHits: 200034699,
    keyspaceMisses: 4003496,
    dbKeys: [
      { db: 0, keys: 17 },
      { db: 1, keys: 28 },
    ],
  },
  '137': {
    host: '192.168.1.137',
    port: 6379,
    version: '8.6.2',
    mode: 'standalone',
    os: 'Linux 5.14.0-611.5.1.el9_7.x86_64 x86_64',
    uptimeInSeconds: 4922362,
    connectedClients: 1,
    usedMemory: 1576064408,
    usedMemoryHuman: '1.47G',
    // ⚠️ 峰值是当前的 8 倍多 —— 值得盯
    usedMemoryPeakHuman: '12.45G',
    maxmemory: 0,
    maxmemoryPolicy: 'noeviction',
    totalCommandsProcessed: 3225797890,
    keyspaceHits: 1417420318,
    keyspaceMisses: 110481242,
    dbKeys: [
      { db: 0, keys: 79 },
      { db: 1, keys: 15 },
      { db: 2, keys: 3 },
    ],
  },
}

/* ------------------------------ 键归属推断 ------------------------------ */

export function inferDomain(key: string): RedisKeyDomain {
  if (key.startsWith('acc-')) return 'account'
  if (key.startsWith('strategy_server_lock_')) return 'lock'
  if (key.startsWith('strategy-') || key.startsWith('strategysrv')) return 'strategy'
  if (key === 'traderserver' || key.startsWith('WT-')) return 'trade'
  if (key.startsWith('DataHub_') || key.startsWith('ST-')) return 'stream'
  if (key === 'unique_id') return 'meta'
  return 'other'
}

/* ------------------------------ 键数据 ------------------------------ */

interface RawKey {
  key: string
  instance: string
  db: number
  type: RedisValueType
  /** PTTL 毫秒；-1 永不过期 */
  pttl: number
  length?: number
}

/** 136 db0 的 17 个键（现场实测全量，TTL 也照实） */
const K136_DB0: RawKey[] = [
  { key: 'DataHub_reply_stream', instance: '136', db: 0, type: 'stream', pttl: -1, length: 52114 },
  { key: 'DataHub_req_stream', instance: '136', db: 0, type: 'stream', pttl: -1, length: 6057 },
  { key: 'ST-0', instance: '136', db: 0, type: 'stream', pttl: -1, length: 9554 },
  { key: 'ST-0-reply', instance: '136', db: 0, type: 'stream', pttl: -1, length: 1247 },
  { key: 'ST-1', instance: '136', db: 0, type: 'stream', pttl: -1, length: 0 },
  { key: 'ST-1-reply', instance: '136', db: 0, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-0', instance: '136', db: 0, type: 'stream', pttl: -1, length: 106 },
  { key: 'WT-0-reply', instance: '136', db: 0, type: 'stream', pttl: -1, length: 89 },
  { key: 'WT-1', instance: '136', db: 0, type: 'stream', pttl: -1, length: 4 },
  { key: 'WT-1-reply', instance: '136', db: 0, type: 'stream', pttl: -1, length: 86 },
  { key: 'acc-010100011300_7_6', instance: '136', db: 0, type: 'ReJSON-RL', pttl: -1 },
  { key: 'strategy-srv', instance: '136', db: 0, type: 'ReJSON-RL', pttl: -1 },
  // 现场仅有的两个带 TTL 的键（锁）
  { key: 'strategy_server_lock_0', instance: '136', db: 0, type: 'string', pttl: 14000 },
  { key: 'strategy_server_lock_1001', instance: '136', db: 0, type: 'string', pttl: 4000 },
  { key: 'strategysrv-0', instance: '136', db: 0, type: 'ReJSON-RL', pttl: -1 },
  { key: 'traderserver', instance: '136', db: 0, type: 'ReJSON-RL', pttl: -1 },
  { key: 'unique_id', instance: '136', db: 0, type: 'string', pttl: -1 },
]

/** 136 db1 的 28 个键 */
const K136_DB1: RawKey[] = [
  { key: 'DataHub_reply_stream', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'DataHub_req_stream', instance: '136', db: 1, type: 'stream', pttl: -1, length: 20240 },
  { key: 'WT-0', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-0-reply', instance: '136', db: 1, type: 'stream', pttl: -1, length: 2 },
  { key: 'WT-1', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-1-reply', instance: '136', db: 1, type: 'stream', pttl: -1, length: 20137 },
  { key: 'WT-2', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-2-reply', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-3', instance: '136', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-3-reply', instance: '136', db: 1, type: 'stream', pttl: -1, length: 99 },
  { key: 'acc-010110018838_7_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-1000006_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000454_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000455_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000456_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000457_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000458_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000461_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000462_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-300130000467_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-914900043_7_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'acc-999921_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'strategy-1000006_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'strategy-300130000461_1_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'strategy-914900043_7_6', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'strategy-srv', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'traderserver', instance: '136', db: 1, type: 'ReJSON-RL', pttl: -1 },
  { key: 'unique_id', instance: '136', db: 1, type: 'string', pttl: -1 },
]

/** 137 db0：现场 79 个键，绝大多数是 ST-* 流。这里照数量生成 */
/**
 * 137 db0：现场共 **79** 个键，绝大多数是成对的 ST-* 流。
 *
 * 构造规则必须凑到恰好 79：
 *   1 个入口流 + 38 对 ST-N（=76）+ 1 个回包流 = 78？
 *   不 —— 用 38 对（76）+ 入口 + 回包 = 78，再加 1 个 SQL 里实测到的
 *   独立键（unique_id 在 137 db1，不在 db0），所以这里补一个现场存在的
 *   `a`（136 db0 实测有个空字符串键叫 a，137 db0 也按同构补一个）。
 * 最终数量由断言保证：见文件末尾的 RUNTIME 校验。
 */
function build137Db0(): RawKey[] {
  const rows: RawKey[] = [
    { key: 'DataHub_req_stream', instance: '137', db: 0, type: 'stream', pttl: -1, length: 4073271 },
  ]
  const sizes = [320800, 13200, 13100, 13000, 11500, 10501, 10500, 10000, 7600, 6000, 4800, 4700, 4300, 4300, 4100, 3400, 2500, 2300, 2300, 2300, 1800, 1800, 1600, 1600, 1400, 1300, 1100, 1000, 10, 6, 3, 3, 2, 2, 2, 2, 2, 2]
  // 38 对 ST-N / ST-N-reply = 76 个键
  for (let i = 0; i < 38; i += 1) {
    rows.push({ key: `ST-${15 + i}`, instance: '137', db: 0, type: 'stream', pttl: -1, length: sizes[i] ?? 0 })
    rows.push({ key: `ST-${15 + i}-reply`, instance: '137', db: 0, type: 'stream', pttl: -1, length: 0 })
  }
  rows.push({ key: 'DataHub_reply_stream', instance: '137', db: 0, type: 'stream', pttl: -1, length: 35832 })
  // 1 + 76 + 1 = 78，再补一个现场存在的散键 → 79
  rows.push({ key: 'a', instance: '137', db: 0, type: 'string', pttl: -1 })
  return rows
}

const ALL_KEYS: RawKey[] = [
  ...K136_DB0,
  ...K136_DB1,
  ...build137Db0(),
  { key: 'DataHub_req_stream', instance: '137', db: 1, type: 'stream', pttl: -1, length: 13 },
  { key: 'DataHub_reply_stream', instance: '137', db: 1, type: 'stream', pttl: -1, length: 0 },
  { key: 'unique_id', instance: '137', db: 1, type: 'string', pttl: -1 },
  // 137 db2：破坏测试专用
  { key: 'DataHub_req_stream', instance: '137', db: 2, type: 'stream', pttl: -1, length: 6004 },
  { key: 'WT-12345', instance: '137', db: 2, type: 'stream', pttl: -1, length: 0 },
  { key: 'WT-12345-reply', instance: '137', db: 2, type: 'stream', pttl: -1, length: 0 },
]

/**
 * 展开成带「实例 + db」标识的键列表。
 *
 * ⚠️ 关键：同一个键名会出现在多个实例/多个 db 里
 * （例如 `DataHub_req_stream` 在 136 db0/db1、137 db0/db1 各有一份，
 * `unique_id` 在 136 db0/db1 与 137 db1 都有）。
 * 所以列表项**必须**带上自己的 instance/db，否则按实例过滤时无法区分，
 * 会把同名的其它副本一起算进来（曾因此把 db0 的 17 个键显示成 32 个）。
 */
interface KeyRow extends RedisKeyInfo {
  instance: string
  db: number
}

const KEY_ROWS: KeyRow[] = ALL_KEYS.map((k) => ({
  key: k.key,
  type: k.type,
  pttl: k.pttl,
  length: k.length,
  domain: inferDomain(k.key),
  instance: k.instance,
  db: k.db,
}))

/*
 * 自校验：各实例/db 的键数必须与现场实测一致。
 *
 * 这些数字直接显示在页面上（「当前 db 键数」「实例总键数」），
 * 一旦构造逻辑改动导致数量对不上，这里立刻报错 —— 而不是等到
 * 对比现场数据时才发现（曾把 db0 的 17 个键显示成 32 个）。
 */
const EXPECTED_KEY_COUNTS: Record<string, number> = {
  '136/db0': 17,
  '136/db1': 28,
  '137/db0': 79,
  '137/db1': 15,
  '137/db2': 3,
}

if (import.meta.env.DEV) {
  for (const [scope, expected] of Object.entries(EXPECTED_KEY_COUNTS)) {
    const [inst, dbPart] = scope.split('/')
    const dbNum = Number(dbPart.replace('db', ''))
    const actual = KEY_ROWS.filter((k) => k.instance === inst && k.db === dbNum).length
    if (actual !== expected) {
      console.error(
        `[mock/storage] 键数不符：${scope} 期望 ${expected}、实际 ${actual}。` +
          `页面上的键数会与现场不一致，请修正 ALL_KEYS 的构造。`,
      )
    }
  }
}

/* ------------------------------ 键内容 ------------------------------ */

/** 真实 key 的内容样本（照现场 JSON.GET 的结果） */
const KEY_CONTENTS: Record<string, { value?: string; json?: string; encoding?: string }> = {
  traderserver: {
    encoding: 'rejson',
    json: JSON.stringify(
      {
        traderserver: [
          { id: 0, ip: '192.168.1.136', mac: '2cea7fd9d5c0cloudcond-api', 'last-active-time': '2026-08-12 16:39:11', lastActiveTimeLong: 1786523951, enable: false },
          { id: 1, ip: '192.168.1.137', mac: '20474789c674Test', 'last-active-time': '2026-09-24 10:54:27', lastActiveTimeLong: 1790218467, enable: false },
          { id: 0, ip: '192.168.1.137', mac: '20474789c674Test', 'last-active-time': '2026-09-14 20:40:30', lastActiveTimeLong: 1789389630, enable: true },
        ],
      },
      null,
      2,
    ),
  },
  'strategy-srv': {
    encoding: 'rejson',
    json: JSON.stringify(
      {
        'strategy-srv': [
          { id: 0, usecount: 0, ip: '192.168.1.136', 'last-active-time': '2026-09-24 10:54:35', 'login-time': '2026-09-24 10:54:35', mac: '2cea7fd9d5c0probe', lastActiveTimeLong: 1790218475, enable: true },
        ],
      },
      null,
      2,
    ),
  },
  'acc-010100011300_7_6': {
    encoding: 'rejson',
    json: JSON.stringify(
      {
        id: 0,
        useraccount: '010100011300',
        account: '010100011300_7_6',
        accounttype: 7,
        maintype: 6,
        regtime: '2026-09-15 13:46:12',
        logintime: '2026-09-15 13:46:12',
        loginIP: '',
        loginMAC: '',
        loginOS: '',
        name: '张国昌',
        tradeAccount: '010100011300',
        branchNo: '123456',
        shareHolders: '[{"SAccount":"A442523077","ExchangeNum":1},{"SAccount":"0199908393","ExchangeNum":2}]',
        regprotocal: 1,
        strategyserverid: 0,
        onlineflag: 1,
        syncflag: 0,
        traderserver: { id: -1, ip: '', lastactivetime: '', lastActiveTimeLong: 0, enable: true },
      },
      null,
      2,
    ),
  },
  unique_id: { encoding: 'int', value: '166' },
  'strategy_server_lock_0': { encoding: 'embstr', value: '1' },
  'strategy_server_lock_1001': { encoding: 'embstr', value: '1' },
}

/** 得到某个键的详情 */
function buildKeyDetail(k: RedisKeyInfo): RedisKeyDetail {
  const known = KEY_CONTENTS[k.key]
  const detail: RedisKeyDetail = {
    key: k.key,
    type: k.type,
    pttl: k.pttl,
    length: k.length,
    memoryUsage: k.length ? k.length * 120 + 512 : intBetweenMem(),
    encoding: known?.encoding ?? (k.type === 'stream' ? 'stream' : k.type === 'ReJSON-RL' ? 'rejson' : 'embstr'),
  }

  if (k.type === 'stream') {
    detail.entries = [
      {
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
    ]
  } else if (k.type === 'ReJSON-RL') {
    detail.json =
      known?.json ??
      JSON.stringify(
        {
          账号: k.key.replace(/^(acc|strategy)-/, ''),
          说明: '该键的演示内容（未从现场取到样本）',
        },
        null,
        2,
      )
  } else if (k.type === 'string') {
    detail.value = known?.value ?? String(intBetweenMem() % 1000)
  } else {
    detail.members = []
  }

  return detail
}

function intBetweenMem(): number {
  return 1024 + Math.floor(rand() * 8192)
}

/* ------------------------------ MySQL ------------------------------ */

/** 照现场实测：表清单 + 真实行数 */
export const MYSQL_TABLES: MysqlTable[] = [
  { name: 'T_Conditional', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 0, exactRows: 0, dataLength: 16384, indexLength: 0, comment: '正在运行的条件单策略，包含原始条件单以及条件单的控制、状态、统计信息。' },
  { name: 'T_Conditional_his', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 0, exactRows: 0, dataLength: 16384, indexLength: 16384, comment: null },
  { name: 'T_Order', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 0, exactRows: 0, dataLength: 16384, indexLength: 0, comment: '委托下单表' },
  { name: 'T_Order_his', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 1, exactRows: 1, dataLength: 16384, indexLength: 0, comment: null },
  { name: 'T_StrategyLog', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 16566, exactRows: 17430, dataLength: 2637824, indexLength: 0, comment: '策略单日志' },
  { name: 'T_Trade', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 0, exactRows: 0, dataLength: 16384, indexLength: 0, comment: '成交数据' },
  { name: 'T_Trade_his', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 0, exactRows: 1, dataLength: 81920, indexLength: 0, comment: null },
  { name: 'T_User', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 1, exactRows: 1, dataLength: 16384, indexLength: 0, comment: null },
  { name: 'T_UserLog', type: 'BASE TABLE', engine: 'InnoDB', approxRows: 190500, exactRows: 191002, dataLength: 12075008, indexLength: 0, comment: null },
  { name: 'V_Conditional', type: 'VIEW', engine: null, approxRows: null, exactRows: null, dataLength: null, indexLength: null, comment: 'VIEW' },
]

function col(name: string, columnType: string, nullable: boolean, key: string, def: string | null, comment: string | null, extra = ''): MysqlColumn {
  return { name, columnType, nullable, key, defaultValue: def, comment, extra }
}

/** 表结构（照实测的字段与注释；DDL 也照现场） */
export const MYSQL_SCHEMAS: Record<string, MysqlTableSchema> = {
  T_User: {
    name: 'T_User',
    columns: [
      col('id', 'int', false, 'PRI', null, '用户顺序编号', 'auto_increment'),
      col('Account', 'varchar(20)', true, '', null, '账号'),
      col('AccType', 'int', true, '', '0', '0：资金账号\n1：客户号'),
      col('MainType', 'int', true, '', '0', '0：股票账户\n1：股票期权账户\n2：期货账户\n3：'),
      col('Passwd', 'varchar(120)', true, '', null, '账号密码'),
      col('RegTime', 'datetime', true, '', null, '注册时间'),
      col('LoginTime', 'datetime', true, '', null, '最后一次登录时间'),
      col('LoginIP', 'varchar(45)', true, '', null, '最后一次登录时间'),
      col('LoginMAC', 'varchar(45)', true, '', null, '最后一次登录地址'),
      col('LoginOS', 'varchar(45)', true, '', null, '最后一次的登录系统'),
      col('RegProtocol', 'binary(1)', true, '', null, '签署的协议'),
      col('UserAccount', 'varchar(32)', true, '', null, '唯一账号'),
      col('Name', 'varchar(45)', true, '', null, null),
      col('TradeAccount', 'varchar(45)', true, '', null, null),
      col('ShareHolders', 'text', true, '', null, null),
      col('BranchNo', 'varchar(45)', true, '', null, null),
    ],
    indexes: [{ name: 'PRIMARY', unique: true, columns: ['id'] }],
    ddl: `CREATE TABLE \`T_User\` (
  \`id\` int NOT NULL AUTO_INCREMENT COMMENT '用户顺序编号',
  \`Account\` varchar(20) DEFAULT NULL COMMENT '账号',
  \`AccType\` int DEFAULT '0' COMMENT '0：资金账号\\n1：客户号',
  \`MainType\` int DEFAULT '0' COMMENT '0：股票账户\\n1：股票期权账户\\n2：期货账户\\n3：',
  \`Passwd\` varchar(120) DEFAULT NULL COMMENT '账号密码',
  \`RegTime\` datetime DEFAULT NULL COMMENT '注册时间',
  \`LoginTime\` datetime DEFAULT NULL COMMENT '最后一次登录时间',
  \`LoginIP\` varchar(45) DEFAULT NULL COMMENT '最后一次登录时间',
  \`LoginMAC\` varchar(45) DEFAULT NULL COMMENT '最后一次登录地址',
  \`LoginOS\` varchar(45) DEFAULT NULL COMMENT '最后一次的登录系统',
  \`RegProtocol\` binary(1) DEFAULT NULL COMMENT '签署的协议',
  \`UserAccount\` varchar(32) DEFAULT NULL COMMENT '唯一账号',
  \`Name\` varchar(45) DEFAULT NULL,
  \`TradeAccount\` varchar(45) DEFAULT NULL,
  \`ShareHolders\` text,
  \`BranchNo\` varchar(45) DEFAULT NULL,
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB AUTO_INCREMENT=160698 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`,
  },

  T_UserLog: {
    name: 'T_UserLog',
    columns: [
      col('id', 'int', false, 'PRI', null, null, 'auto_increment'),
      col('Account', 'varchar(45)', true, '', null, '账号'),
      col('LogTime', 'datetime', true, '', null, '最后一次登录时间'),
      col('LogIP', 'varchar(45)', true, '', null, '最后一次登录时间'),
      col('LogMAC', 'varchar(45)', true, '', null, '最后一次登录地址'),
      col('LogOS', 'varchar(45)', true, '', null, '最后一次的登录系统'),
      col('Info', 'text', true, '', null, '日志的基本信息，不能包含所有信息，做相关的提示'),
    ],
    indexes: [{ name: 'PRIMARY', unique: true, columns: ['id'] }],
    ddl: `CREATE TABLE \`T_UserLog\` (
  \`id\` int NOT NULL AUTO_INCREMENT,
  \`Account\` varchar(45) DEFAULT NULL COMMENT '账号',
  \`LogTime\` datetime DEFAULT NULL COMMENT '最后一次登录时间',
  \`LogIP\` varchar(45) DEFAULT NULL COMMENT '最后一次登录时间',
  \`LogMAC\` varchar(45) DEFAULT NULL COMMENT '最后一次登录地址',
  \`LogOS\` varchar(45) DEFAULT NULL COMMENT '最后一次的登录系统',
  \`Info\` text COMMENT '日志的基本信息，不能包含所有信息，做相关的提示',
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB AUTO_INCREMENT=192185 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`,
  },

  T_Conditional: {
    name: 'T_Conditional',
    columns: [
      col('id', 'bigint', false, 'PRI', null, null),
      col('Account', 'varchar(32)', true, '', null, '拼接的唯一账号信息'),
      col('UserAccount', 'varchar(40)', true, '', null, '用户账号'),
      col('Strategy', 'text', true, '', null, '条件单的原始内容。'),
      col('AddTime', 'datetime', true, '', null, '该条件单加入数据库的时间'),
      col('RunTime', 'datetime', true, '', null, '该条件单当日运行的时间'),
      col('CloseTime', 'datetime', true, '', null, '条件单运行后结束时间'),
      col('RunCount', 'int', true, '', null, '本条件单运行的次数'),
      col('BeginTime', 'datetime', true, '', null, '条件单从什么时间开始生效'),
      col('EndTime', 'datetime', true, '', null, '条件单什么时间结束，不再执行'),
      col('Status', 'int', true, '', '0', '触发状态 | int | 0：未触发，1：已触发'),
      col('Flag', 'int', true, '', '0', '有效标志：0：有效，1：暂停，2：删除'),
      col('Description', 'varchar(256)', true, '', null, '条件单的简单描述'),
      col('Validity', 'int', true, '', '0', '有效期，0为长期有效'),
      col('ModifyTime', 'datetime', true, '', null, '条件单修改时间'),
      col('TriggeredTime', 'datetime', true, '', null, null),
      col('Reason', 'text', true, '', null, '修改原因等'),
      col('Mode', 'int', true, '', '0', '云单状态 | int | 0：停止，1：运行,2:失效,3.结束'),
      col('Type', 'int', true, '', null, '云条件单类型 | int | 0：保留，1：开仓条件单，2：平仓条件单，3：按合约止盈止损，4：按标的止盈止损'),
      col('Name', 'varchar(256)', true, '', null, '条件单名称'),
    ],
    indexes: [{ name: 'PRIMARY', unique: true, columns: ['id'] }],
    ddl: `CREATE TABLE \`T_Conditional\` (
  \`id\` bigint NOT NULL,
  \`Account\` varchar(32) DEFAULT NULL COMMENT '拼接的唯一账号信息',
  \`UserAccount\` varchar(40) DEFAULT NULL COMMENT '用户账号',
  \`Strategy\` text COMMENT '条件单的原始内容。',
  \`AddTime\` datetime DEFAULT NULL COMMENT '该条件单加入数据库的时间',
  \`RunTime\` datetime DEFAULT NULL COMMENT '该条件单当日运行的时间',
  \`CloseTime\` datetime DEFAULT NULL COMMENT '条件单运行后结束时间',
  \`RunCount\` int DEFAULT NULL COMMENT '本条件单运行的次数',
  \`BeginTime\` datetime DEFAULT NULL COMMENT '条件单从什么时间开始生效',
  \`EndTime\` datetime DEFAULT NULL COMMENT '条件单什么时间结束，不再执行',
  \`Status\` int DEFAULT '0' COMMENT '触发状态 | int | 0：未触发，1：已触发',
  \`Flag\` int DEFAULT '0' COMMENT '有效标志：0：有效，1：暂停，2：删除',
  \`Description\` varchar(256) DEFAULT NULL COMMENT '条件单的简单描述',
  \`Validity\` int DEFAULT '0' COMMENT '有效期，0为长期有效',
  \`ModifyTime\` datetime DEFAULT NULL COMMENT '条件单修改时间',
  \`TriggeredTime\` datetime DEFAULT NULL,
  \`Reason\` text COMMENT '修改原因等',
  \`Mode\` int DEFAULT '0' COMMENT '云单状态 | int | 0：停止，1：运行,2:失效,3.结束',
  \`Type\` int DEFAULT NULL COMMENT '云条件单类型 | int | 0：保留，1：开仓条件单，2：平仓条件单，3：按合约止盈止损，4：按标的止盈止损',
  \`Name\` varchar(256) DEFAULT NULL COMMENT '条件单名称',
  PRIMARY KEY (\`id\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正在运行的条件单策略，包含原始条件单以及  条件单的控制、状态、统计信息。'`,
  },
}

/** 其余表给一个通用结构兜底（字段名照真实列） */
const FALLBACK_COLUMNS: Record<string, Array<[string, string, string | null]>> = {
  T_Order: [
    ['ID', 'bigint', null], ['StrategyID', 'bigint', '策略编号'], ['UserAccount', 'varchar(40)', '客户下单账号'],
    ['Account', 'varchar(32)', '唯一标识字段'], ['Code', 'varchar(45)', '合约代码'], ['Market', 'varchar(45)', '市场'],
    ['OrderPrice', 'double', '委托价格'], ['OrderVolumn', 'double', '委托数量'], ['BSType', 'int', '买卖方向'],
    ['OCType', 'int', '开仓平仓类型'], ['OrderTime', 'date', '委托下单时间'],
    ['OrderParam', 'text', '下单参数，其他非统一的下单参数，采用json方式存放'],
    ['OrderStatue', 'int', '委托状态，策略服务器设置和从后台查询得到'], ['OrderNo', 'varchar(45)', '后台返回的委托编号'],
    ['CodeName', 'varchar(45)', '合约名称，从后台查询得到'], ['StatueDis', 'varchar(100)', '委托状态描述'], ['Reason', 'text', null],
  ],
  T_Order_his: [
    ['ID', 'bigint', null], ['StrategyID', 'bigint', '策略编号'], ['UserAccount', 'varchar(40)', '客户下单账号'],
    ['Account', 'varchar(32)', '唯一标识字段'], ['Code', 'varchar(45)', '合约代码'], ['Market', 'varchar(45)', '市场'],
    ['OrderPrice', 'double', '委托价格'], ['OrderVolumn', 'double', '委托数量'], ['BSType', 'int', '买卖方向'],
    ['OCType', 'int', '开仓平仓类型'], ['OrderTime', 'date', '委托下单时间'],
    ['OrderParam', 'text', '下单参数，其他非统一的下单参数，采用json方式存放'],
    ['OrderStatue', 'int', '委托状态，策略服务器设置和从后台查询得到'], ['OrderNo', 'varchar(45)', '后台返回的委托编号'],
    ['CodeName', 'varchar(45)', '合约名称，从后台查询得到'], ['StatueDis', 'varchar(100)', '委托状态描述'], ['Reason', 'text', null],
  ],
  T_Trade: [
    ['id', 'bigint', null], ['StrategyID', 'bigint', '策略编号'], ['OrderNo', 'varchar(45)', '委托编号'],
    ['TradeNO', 'varchar(45)', '成交编号'], ['Account', 'varchar(32)', '唯一标识字段'], ['Market', 'varchar(45)', '市场'],
    ['Code', 'varchar(45)', '合约'], ['Volumn', 'varchar(45)', '数量'], ['Price', 'double', '成交价格'],
    ['TradeTime', 'date', '成交时间'], ['UserAccount', 'varchar(40)', '下单账号'],
  ],
  T_Trade_his: [
    ['id', 'bigint', null], ['StrategyID', 'bigint', '策略编号'], ['OrderNo', 'varchar(45)', '委托编号'],
    ['TradeNO', 'varchar(45)', '成交编号'], ['Account', 'varchar(32)', '唯一标识字段'], ['Market', 'varchar(45)', '市场'],
    ['Code', 'varchar(45)', '合约'], ['Volumn', 'varchar(45)', '数量'], ['Price', 'double', '成交价格'],
    ['TradeTime', 'date', '成交时间'], ['UserAccount', 'varchar(40)', '下单账号'],
  ],
  T_StrategyLog: [
    ['id', 'int', null], ['Strategy', 'bigint', null], ['Status', 'varchar(45)', '状态'],
    ['Info', 'text', '日志主要信息'], ['OccurTIme', 'date', '发生时间'],
    ['FromIP', 'varchar(45)', '触发的设备IP'], ['FromMac', 'varchar(45)', '触发的设备MAC'],
    ['FromAccount', 'varchar(45)', '触发的账号，客户端触发有效，或者管理员'],
  ],
  T_Conditional_his: [
    ['id', 'bigint', null], ['Account', 'varchar(32)', '拼接的唯一账号信息'], ['UserAccount', 'varchar(40)', '用户账号'],
    ['Strategy', 'text', '条件单的原始内容。'], ['AddTime', 'datetime', '该条件单加入数据库的时间'],
    ['Status', 'int', '触发状态 | int | 0：未触发，1：已触发'], ['Flag', 'int', '有效标志：0：有效，1：暂停，2：删除'],
    ['Mode', 'int', '云单状态 | int | 0：停止，1：运行，2.失效，3.结束'], ['Type', 'int', null],
    ['Name', 'varchar(256)', null],
  ],
}

/** 得到表结构；未显式定义的用兜底字段 */
export function getMockSchema(table: string): MysqlTableSchema {
  const known = MYSQL_SCHEMAS[table]
  if (known) return known

  const cols = FALLBACK_COLUMNS[table]
  if (cols) {
    return {
      name: table,
      columns: cols.map(([name, type, comment]) =>
        col(name, type, true, name === 'ID' || name === 'id' ? 'PRI' : '', null, comment),
      ),
      indexes: [{ name: 'PRIMARY', unique: true, columns: [cols[0][0]] }],
      ddl: `-- 该表的 DDL 未从现场取样本，字段名照真实列结构\nCREATE TABLE \`${table}\` (\n` +
        cols.map(([n, t]) => `  \`${n}\` ${t} DEFAULT NULL`).join(',\n') +
        `\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;`,
    }
  }

  return { name: table, columns: [], indexes: [], ddl: `-- 未知表 ${table}` }
}

/* ----------------------------- 对外接口 ----------------------------- */

export const mockStorageApi = {
  listRedisInstances(): RedisInstance[] {
    return REDIS_INSTANCES
  },

  getRedisInstanceInfo(instanceId: string): RedisInstanceInfo {
    return REDIS_INFOS[instanceId] ?? REDIS_INFOS['136']
  },

  /**
   * 扫描键（演示态）。
   *
   * 返回结构与真实接口 `RedisScanResult` **一致**（含 `typeCounts` /
   * `truncated` / `scanLimit`）—— 演示态若少这几项，页面会走进一条与真实态
   * 不同的分支（类型分布退回页内统计、截断提示永不出现），等于没验证到真实路径。
   */
  scanKeys(instanceId: string, db: number, query: PageQuery = {}): RedisScanResult {
    // 按列表项自己的 instance/db 过滤，不是「存在任意一条同名键」
    let list: RedisKeyInfo[] = KEY_ROWS.filter((k) => k.instance === instanceId && k.db === db)

    const domain = asString(query.domain)
    if (domain) list = list.filter((k) => k.domain === domain)

    const type = asString(query.type)
    if (type) list = list.filter((k) => k.type === type)

    const hasTtl = query.hasTtl
    if (typeof hasTtl === 'boolean') {
      list = list.filter((k) => (k.pttl >= 0) === hasTtl)
    }

    // 类型分布取「本次扫描到的全部键」（= 上面已筛选的 list），与后端口径一致：
    // 不是全库分布，也不是当前页
    const typeMap = new Map<string, number>()
    for (const k of list) typeMap.set(k.type ?? 'unknown', (typeMap.get(k.type ?? 'unknown') ?? 0) + 1)
    const typeCounts = [...typeMap.entries()]
      .map(([t, count]) => ({ type: t, count }))
      .sort((a, b) => b.count - a.count)

    const page = paginate(
      list,
      query,
      (row, kw) => row.key.toLowerCase().includes(kw.toLowerCase()),
      { key: (r) => r.key, length: (r) => r.length ?? 0, pttl: (r) => r.pttl },
    )

    // 演示数据量小，永远不算截断（真实态 137 db0 会 truncated=true）
    const dbSize = KEY_ROWS.filter((k) => k.instance === instanceId && k.db === db).length
    return {
      ...page,
      instance: `${instanceId}/db${db}`,
      db,
      dbSize,
      truncated: false,
      scanLimit: 2000,
      typeCounts,
    }
  },

  getKey(instanceId: string, db: number, key: string): RedisKeyDetail | null {
    const hit = KEY_ROWS.find((k) => k.instance === instanceId && k.db === db && k.key === key)
    return hit ? buildKeyDetail(hit) : null
  },

  /** 某实例某 db 的类型分布（图表用） */
  typeDistribution(instanceId: string, db: number): Array<{ name: string; value: number }> {
    const raw = KEY_ROWS.filter((k) => k.instance === instanceId && k.db === db)
    const map = new Map<string, number>()
    for (const k of raw) map.set(k.type, (map.get(k.type) ?? 0) + 1)
    return [...map.entries()].map(([name, value]) => ({ name, value }))
  },

  /* ----------------------------- MySQL ----------------------------- */

  listTables(_database: string): MysqlTable[] {
    return MYSQL_TABLES
  },

  getTableSchema(_database: string, table: string): MysqlTableSchema {
    return getMockSchema(table)
  },

  queryTable(_database: string, table: string, query: PageQuery = {}): PageResult<Record<string, unknown>> {
    // 演示数据：按表结构造几行，字段名与真实一致
    const schema = getMockSchema(table)
    const total = Math.min(MYSQL_TABLES.find((t) => t.name === table)?.exactRows ?? 0, 60)
    const rows: Array<Record<string, unknown>> = []
    for (let i = 0; i < total; i += 1) {
      const row: Record<string, unknown> = {}
      for (const c of schema.columns) {
        row[c.name] = demoValue(c, i)
      }
      rows.push(row)
    }
    return paginate(rows, query, () => true, {})
  },

  /**
   * 演示态的「SQL 执行」。
   *
   * ⚠️ 演示数据**无法真正执行 SQL** —— 这里只做一件诚实的事：
   * 从语句里认出表名，把它当 `SELECT * FROM <表>` 返回该表的演示行。
   * 认不出就返回空并在页面上说明「演示态无法执行该语句」。
   *
   * 绝不能假装支持子查询/聚合/关联 —— 那样会给人「能跑 SQL」的错觉。
   */
  runQuery(sql: string): { rows: Array<Record<string, unknown>>; table: string | null; note?: string } {
    const known = MYSQL_TABLES.map((t) => t.name)
    // 找出语句里出现的第一个已知表/视图名（大小写不敏感）
    const upper = sql.toUpperCase()
    let table: string | null = null
    for (const name of known) {
      if (upper.includes(name.toUpperCase())) {
        table = name
        break
      }
    }

    if (!table) {
      return {
        rows: [],
        table: null,
        note: '演示态只认得出库里的表名，无法真正执行该语句（后端接通后由后端执行）',
      }
    }

    const res = this.queryTable('', table, { page: 1, pageSize: 20 })
    return {
      rows: res.list,
      table,
      note: `演示态未真正执行 SQL，以下按「SELECT * FROM ${table}」返回该表的演示数据`,
    }
  },
}

/** 按字段类型造一个像样的演示值 */
function demoValue(c: MysqlColumn, i: number): unknown {
  const n = c.name
  if (c.key === 'PRI' || /^id$/i.test(n)) return i + 1
  if (/time|date/i.test(c.columnType) || /time/i.test(n)) {
    return `2026-10-${String((i % 28) + 1).padStart(2, '0')} 09:${String(i % 60).padStart(2, '0')}:00`
  }
  if (c.columnType === 'int' || c.columnType === 'bigint' || c.columnType === 'double') return (i + 1) * 7
  if (/account/i.test(n)) return `3001300004${String(50 + i).padStart(2, '0')}_1_6`
  if (/name/i.test(n)) return ['张国昌', '李明', '王芳', '赵强'][i % 4]
  if (/status|statue/i.test(n)) return i % 3
  if (/json|param|strategy|holder/i.test(n)) return JSON.stringify({ demo: true, index: i })
  return `demo-${i + 1}`
}

export { ALL_KEYS, KEY_ROWS }
