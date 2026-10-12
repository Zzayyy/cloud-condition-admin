# -*- coding: utf-8 -*-
"""
生成后端系统管理模块的 SQL 文件（DDL + 测试数据）。

输出：
  cloud-condition-admin/backend/sql/01_schema_admin.sql
  cloud-condition-admin/backend/sql/02_data_admin.sql

只创建/写入 T_Admin* 前缀的新表，绝不触碰 qianlong_cloud 库里已有的
T_Conditional / T_Order / T_Trade / T_User / T_UserLog / T_StrategyLog / V_Conditional。
"""
from __future__ import annotations

import datetime as dt
import os
import random
import textwrap

import bcrypt

from paths import sql_dir

OUT_DIR = sql_dir()

# 固定种子 -> 每次生成的测试数据一致，便于核对界面
RAND = random.Random(20261015)

# 演示/联调口令（与前端 src/api/mock/system.ts 的演示口令一致，方便后端联调）
PWD_ADMIN = "Admin@2026"
PWD_OPS = "Ops@2026"
PWD_VIEW = "View@2026"


def bcrypt_hash(plain: str) -> str:
    return bcrypt.hashpw(plain.encode("utf-8"), bcrypt.gensalt(rounds=10)).decode("ascii")


def q(value) -> str:
    """SQL 字面量转义。"""
    if value is None:
        return "NULL"
    if isinstance(value, RawSql):
        # 原样输出（用于 NOW() - INTERVAL n MINUTE 这类必须在执行时求值的表达式）
        return value.sql
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, dt.datetime):
        return "'" + value.strftime("%Y-%m-%d %H:%M:%S") + "'"
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


class RawSql:
    """直接写进 SQL 的表达式（不转义）。

    用途：锁定时间必须在**脚本执行时**才求值 —— 见 build_users 里的说明。
    """

    def __init__(self, sql: str):
        self.sql = sql

    def __repr__(self) -> str:  # pragma: no cover
        return f"RawSql({self.sql})"


def rows_to_insert(table: str, columns: list[str], rows: list[list]) -> str:
    col_sql = ", ".join(f"`{c}`" for c in columns)
    out = [f"INSERT INTO `{table}` ({col_sql}) VALUES"]
    body = []
    for row in rows:
        body.append("  (" + ", ".join(q(v) for v in row) + ")")
    out.append(",\n".join(body) + ";")
    return "\n".join(out)


# --------------------------------------------------------------------------- #
# 权限键（与前端 src/config/permissions.ts 完全同源：17 个菜单键 + 7 个操作键）
# --------------------------------------------------------------------------- #

MENU_KEYS = [
    "dashboard", "server-trade", "server-datahub", "server-strategy", "server-stream",
    "conditional", "order", "deal", "strategy-log",
    "account-user", "account-sign", "account-log",
    "storage-redis", "storage-mysql",
    "system-user", "system-role", "system-audit",
]
MENU_PERMS = [f"menu:{n}" for n in MENU_KEYS]

# 17 个菜单页面的标题（与 menu.ts 的 meta.title 一致）
MENU_TITLES = {
    "dashboard": "总览",
    "server-trade": "委托服务器",
    "server-datahub": "数据中台",
    "server-strategy": "策略服务器",
    "server-stream": "消息总线",
    "conditional": "条件单管理",
    "order": "委托单管理",
    "deal": "成交回报管理",
    "strategy-log": "策略日志管理",
    "account-user": "用户账号",
    "account-sign": "签署与股东号",
    "account-log": "登录日志",
    "storage-redis": "Redis 管理",
    "storage-mysql": "数据库管理",
    "system-user": "管理用户与权限",
    "system-role": "角色与权限",
    "system-audit": "操作审计",
}

ACTION_PERMS = [
    ("conditional", "write", "启停 / 删除条件单", "bizdata", "high"),
    ("server-trade", "toggle", "启停委托服务器", "server", "high"),
    ("storage-redis", "delete-key", "删除 Redis 键", "storage", "high"),
    ("storage-mysql", "readonly-sql", "执行只读 SQL", "storage", "medium"),
    ("system-user", "manage", "管理后台账号", "system", "high"),
    ("system-role", "manage", "管理角色与权限", "system", "high"),
    ("system-audit", "export", "导出审计日志", "system", "low"),
]

# 审计动作字典：7 个权限型动作 + 2 个「需留痕但没有独立权限点」的登记型动作
AUDIT_ACTIONS = [
    {
        "action": f"action:{m}:{a}",
        "label": label,
        "module": module,
        "level": level,
    }
    for (m, a, label, module, level) in ACTION_PERMS
]
AUDIT_ACTIONS.append({"action": "system:connection", "label": "修改连接配置", "module": "system", "level": "high"})
AUDIT_ACTIONS.append({"action": "system:login", "label": "管理端登录", "module": "system", "level": "low"})

# 高危动作的典型目标（照现场真实键/表名造，便于一眼看出这是哪类操作）
AUDIT_TARGETS = {
    "action:storage-redis:delete-key": [
        "a", "strategy_server_lock_1001", "WT-1-reply", "DataHub_destroy_stream",
    ],
    "action:conditional:write": ["条件单 300130000461", "条件单 914900043", "条件单 1000006"],
    "action:server-trade:toggle": [
        "traderserver id=1 mac=2cea7fd9d5c0", "traderserver id=3 mac=20474789c674",
    ],
    "action:storage-mysql:readonly-sql": [
        "SELECT * FROM T_UserLog LIMIT 100",
        "SELECT COUNT(*) FROM T_StrategyLog",
        "SELECT * FROM V_Conditional LIMIT 50",
    ],
    "action:system-user:manage": ["test_tmp", "liuyi", "audit01"],
    "action:system-role:manage": ["viewer", "auditor"],
    "action:system-audit:export": ["2026-08 审计导出"],
    "system:connection": ["REDISHOST / REDISSELECT", "DBHOST / DBUSER"],
    "system:login": ["admin", "yangsh"],
}

AUDIT_MESSAGES = {
    "action:storage-redis:delete-key": [
        "删除成功（DEL a = 1）", "键不存在，未产生写入", "拒绝：该键属于 DataHub_* 流，后端要求二次确认",
    ],
    "action:conditional:write": [
        "已下发 remove 协议，策略平台已回执", "已下发 set 协议（停止）", "超时未收到策略平台回执",
    ],
    "action:server-trade:toggle": ["enable 已写回 Redis JSON", "目标节点 id 重复，按 mac 定位成功"],
    "action:storage-mysql:readonly-sql": [
        "返回 100 行（已截断到上限）", "语句含 DELETE，被只读校验拒绝", "执行耗时 128ms",
    ],
    "action:system-user:manage": ["新建账号并分配 viewer", "停用账号，旧 token 已失效", "重置密码并强制下次改密"],
    "action:system-role:manage": ["新增权限点 3 个", "拒绝：内置角色不可删", "权限已保存，涉及 2 个账号"],
    "action:system-audit:export": ["导出 1280 条，格式 CSV"],
    "system:connection": ["配置已保存（密码不回显）", "连通性测试通过"],
    "system:login": ["登录成功", "密码错误（第 2 次）", "账号已停用，拒绝登录"],
}

LOGIN_FAIL_REASONS = ["密码错误", "账号不存在", "账号已停用", "账号已锁定"]
LOGIN_OS = [
    "Windows 11 / Chrome 141", "Windows 10 / Edge 141",
    "macOS 15 / Safari 18", "CentOS 7 / curl",
]
RISKY_IPS = ["203.0.113.44", "198.51.100.7"]
SAFE_IPS = ["192.168.1.136", "10.0.0.35", "10.0.0.21"]

NOW = dt.datetime.now().replace(microsecond=0)


def ago(seconds: int) -> dt.datetime:
    return NOW - dt.timedelta(seconds=seconds)


# --------------------------------------------------------------------------- #
# 01_schema_admin.sql
# --------------------------------------------------------------------------- #

SCHEMA_HEADER = """\
-- =============================================================================
-- 云条件单后台管理系统 —— 系统管理模块 建表脚本
-- -----------------------------------------------------------------------------
-- 目标库：qianlong_cloud @ 192.168.1.136:3306
--
-- ⚠️ 本脚本**只创建 T_Admin* 前缀的新表**，不修改、不删除、不清空库里任何已有对象。
--    已有业务对象（T_Conditional / T_Conditional_his / V_Conditional / T_Order /
--    T_Order_his / T_Trade / T_Trade_his / T_User / T_UserLog / T_StrategyLog）
--    一律不动。执行前可用下面的语句自检（应返回空集）：
--
--      SELECT TABLE_NAME FROM information_schema.TABLES
--       WHERE TABLE_SCHEMA='qianlong_cloud' AND TABLE_NAME LIKE 'T_Admin%';
--
-- 字符集/排序规则与库内既有表保持一致：utf8mb4 / utf8mb4_0900_ai_ci
-- 引擎：InnoDB
--
-- 设计说明：
--   * 后台管理账号体系与云单业务用户（T_User / T_UserLog）**是两条完全不同的线**：
--     前者是「谁能登录后台」，后者是客户的钱包账号。不要混用。
--   * 密码**只写不读**：T_AdminUser.passwordHash 存 BCrypt 哈希，
--     任何接口都不得回显明文或哈希。
--   * 列名统一小驼峰，与前端 src/api/domain.ts 的字段名一致，
--     减少一层容易写错的映射（这几张表是全新的，不背负旧表命名习惯）。
-- =============================================================================

-- 建表脚本可重复执行（IF NOT EXISTS），不会因为已存在而中断后续语句。
-- 若要彻底重建，请先执行 00_drop_admin.sql（该脚本同样只针对 T_Admin* 表）。
"""

SCHEMA_BODY = """
-- -----------------------------------------------------------------------------
-- 1. T_AdminRole —— 后台角色
--    builtin = 1 的角色不可删除、code 与名称不可修改
--    （删掉超管角色 = 后台再也进不去，现场没有别的恢复手段）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminRole` (
  `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '角色主键',
  `code`        VARCHAR(32)  NOT NULL                COMMENT '角色编码（唯一）；创建后不可修改，审计靠它认人',
  `name`        VARCHAR(32)  NOT NULL                COMMENT '角色名称',
  `description` VARCHAR(200)     NULL DEFAULT NULL   COMMENT '角色说明（前端字段名为 desc）',
  `builtin`     TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否内置角色：1=内置（不可删、code/名称不可改）',
  `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updatedAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色（系统管理 / 角色与权限）';

-- -----------------------------------------------------------------------------
-- 2. T_AdminRolePermission —— 角色持有的权限键
--    键格式与前端 src/config/permissions.ts 完全同源：
--      menu:<菜单 name>         菜单权限，共 17 个
--      action:<模块>:<动作>     操作权限，共 7 个
--      *                        通配符（超管专用，代表全部权限）
--    刻意不做「父级菜单权限隐式覆盖操作权限」：否则「能看到页面」就等于「能删键」。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminRolePermission` (
  `id`            INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  `roleId`        INT         NOT NULL                COMMENT '角色 id，对应 T_AdminRole.id',
  `permissionKey` VARCHAR(64) NOT NULL                COMMENT '权限键，见前端 permissions.ts',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_perm` (`roleId`, `permissionKey`),
  KEY `idx_admin_role_perm_key` (`permissionKey`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台角色权限点';

-- -----------------------------------------------------------------------------
-- 3. T_AdminUser —— 后台管理账号（**不是**云单业务用户 T_User）
--    passwordHash 只写不读；停用/改权限必须在后端同时让已签发 token 失效。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminUser` (
  `id`                 INT          NOT NULL AUTO_INCREMENT COMMENT '账号主键',
  `username`           VARCHAR(32)  NOT NULL                COMMENT '登录名（唯一）：3-32 位字母/数字/_.-',
  `displayName`        VARCHAR(32)      NULL DEFAULT NULL   COMMENT '显示名（审计要认人）',
  `passwordHash`       VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码哈希，只写不读，接口永不回显',
  `enabled`            TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '启用状态：0=停用（立即不能登录）',
  `locked`             TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '登录失败锁定：1=已锁定',
  `failedAttempts`     INT          NOT NULL DEFAULT 0      COMMENT '当前连续登录失败次数',
  `mustChangePassword` TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否需要下次登录强制改密',
  `lockTime`           DATETIME         NULL DEFAULT NULL   COMMENT '被锁定的时间（用于 lockMinutes 到期自动解锁）',
  `passwordUpdateTime` DATETIME         NULL DEFAULT NULL   COMMENT '最后一次改密时间（用于 expireDays 判定）',
  `lastLoginTime`      DATETIME         NULL DEFAULT NULL   COMMENT '最后一次成功登录时间',
  `lastLoginIP`        VARCHAR(45)      NULL DEFAULT NULL   COMMENT '最后一次成功登录来源 IP',
  `loginCount`         INT          NOT NULL DEFAULT 0      COMMENT '累计成功登录次数',
  `builtin`            TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '内置账号（admin）：不可删除',
  `remark`             VARCHAR(255)     NULL DEFAULT NULL   COMMENT '备注',
  `createdAt`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updatedAt`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_username` (`username`),
  KEY `idx_admin_user_enabled` (`enabled`),
  KEY `idx_admin_user_locked` (`locked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台管理账号（与云单业务用户 T_User 无关）';

-- -----------------------------------------------------------------------------
-- 4. T_AdminUserRole —— 账号与角色的多对多关系（一个账号可挂多个角色）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminUserRole` (
  `id`     INT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId` INT NOT NULL                COMMENT '账号 id，对应 T_AdminUser.id',
  `roleId` INT NOT NULL                COMMENT '角色 id，对应 T_AdminRole.id',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_user_role` (`userId`, `roleId`),
  KEY `idx_admin_user_role_role` (`roleId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台账号-角色关系';

-- -----------------------------------------------------------------------------
-- 5. T_AdminPasswordPolicy —— 密码策略与登录失败锁定策略（单行配置表，id 恒为 1）
--    前端只用它做即时校验提示；真正的判定必须在后端做（前端校验不是安全边界）。
--
--    ⚠️ 2026-10-12 按需求方要求放宽复杂度：只保留「长度 ≥ 6」，
--       不再要求大写 / 小写 / 数字 / 符号（建测试账号时不必再凑字符种类）。
--       「不得与登录名相同」是代码里的固定规则，不占本表字段，仍然生效。
--       「连续失败锁定」与「历史密码不重复」**保留** —— 那是防暴力破解的手段，
--       与密码好不好记无关，放宽复杂度不该顺带把它们也关掉。
--       这里的 DEFAULT 与 02_data_admin.sql 的 INSERT、以及
--       PasswordPolicyService.defaultPolicy() 三处必须保持一致：
--       本表缺行时后端会用代码里的默认值兜底，两边不一致会导致
--       「表里看着是宽松的，实际按严格校验」这种很难查的偏差。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminPasswordPolicy` (
  `id`                      INT        NOT NULL             COMMENT '固定为 1（单行配置表）',
  `minLength`               INT        NOT NULL DEFAULT 6   COMMENT '密码最小长度',
  `requireUpper`            TINYINT(1) NOT NULL DEFAULT 0   COMMENT '是否要求大写字母（0=不要求）',
  `requireLower`            TINYINT(1) NOT NULL DEFAULT 0   COMMENT '是否要求小写字母（0=不要求）',
  `requireDigit`            TINYINT(1) NOT NULL DEFAULT 0   COMMENT '是否要求数字（0=不要求）',
  `requireSymbol`           TINYINT(1) NOT NULL DEFAULT 0   COMMENT '是否要求符号（0=不要求）',
  `maxFailedAttempts`       INT        NOT NULL DEFAULT 5   COMMENT '连续失败达到该次数即锁定，0=不锁定',
  `lockMinutes`             INT        NOT NULL DEFAULT 30  COMMENT '锁定时长（分钟）',
  `expireDays`              INT        NOT NULL DEFAULT 90  COMMENT '密码有效期（天），0=永不过期',
  `forceChangeOnFirstLogin` TINYINT(1) NOT NULL DEFAULT 1   COMMENT '首次登录是否强制改密',
  `historySize`             INT        NOT NULL DEFAULT 5   COMMENT '禁止与最近 N 次密码重复，0=不校验历史',
  `updatedAt`               DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台密码策略与登录失败锁定策略';

-- -----------------------------------------------------------------------------
-- 6. T_AdminPasswordHistory —— 历史密码哈希（支撑策略里的 historySize）
--    只存哈希，绝不存明文。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminPasswordHistory` (
  `id`           INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId`       INT          NOT NULL                COMMENT '账号 id，对应 T_AdminUser.id',
  `passwordHash` VARCHAR(100) NOT NULL                COMMENT '历史密码的 BCrypt 哈希',
  `createdAt`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
  PRIMARY KEY (`id`),
  KEY `idx_admin_pwd_his_user` (`userId`, `createdAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='后台账号历史密码哈希';

-- -----------------------------------------------------------------------------
-- 7. T_AdminLoginLog —— 管理端登录日志
--    ⚠️ 与云单客户的 T_UserLog 不是一张表（那是 19 万行的客户登录日志）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminLoginLog` (
  `id`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`  VARCHAR(32)  NOT NULL                COMMENT '登录名（失败时也记录，可能是并不存在的账号）',
  `loginTime` DATETIME     NOT NULL                COMMENT '登录时间',
  `loginIP`   VARCHAR(45)      NULL DEFAULT NULL   COMMENT '来源 IP',
  `loginOS`   VARCHAR(200)     NULL DEFAULT NULL   COMMENT '客户端 / 操作系统',
  `success`   TINYINT(1)   NOT NULL                COMMENT '是否成功：1=成功，0=失败',
  `info`      VARCHAR(255)     NULL DEFAULT NULL   COMMENT '成功说明或失败原因',
  PRIMARY KEY (`id`),
  KEY `idx_admin_login_time` (`loginTime`),
  KEY `idx_admin_login_username` (`username`),
  KEY `idx_admin_login_success` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理端登录日志（区别于云单用户 T_UserLog）';

-- -----------------------------------------------------------------------------
-- 8. T_AdminAudit —— 管理端操作审计（高危操作必写）
--    action 与前端 permissions.ts 的权限键同源（action:<模块>:<动作>），
--    另有 system:login / system:connection 这类「需留痕但没有独立权限点」的登记型动作。
--    actionLabel 在写入时落库（历史记录的标签不随后续改名而漂移）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `T_AdminAudit` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator`    VARCHAR(32)  NOT NULL                COMMENT '操作人（后台账号登录名）',
  `action`      VARCHAR(64)  NOT NULL                COMMENT '动作键，与 permissions.ts 的权限键同源',
  `actionLabel` VARCHAR(64)  NOT NULL                COMMENT '动作中文名（写入时落库，不随后续改名漂移）',
  `module`      VARCHAR(32)  NOT NULL                COMMENT '归属模块（一级菜单 name，如 storage / system）',
  `level`       VARCHAR(8)   NOT NULL                COMMENT '危险级别：high / medium / low',
  `target`      VARCHAR(255)     NULL DEFAULT NULL   COMMENT '目标对象：键名 / 表名 / 条件单号 / 账号名',
  `result`      VARCHAR(16)  NOT NULL                COMMENT '结果：success / failure',
  `message`     VARCHAR(500)     NULL DEFAULT NULL   COMMENT '失败原因或补充说明（原样展示，不美化）',
  `ip`          VARCHAR(45)      NULL DEFAULT NULL   COMMENT '请求来源 IP',
  `costMs`      INT              NULL DEFAULT NULL   COMMENT '耗时（毫秒）',
  `createdAt`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_admin_audit_created` (`createdAt`),
  KEY `idx_admin_audit_action` (`action`),
  KEY `idx_admin_audit_level` (`level`),
  KEY `idx_admin_audit_operator` (`operator`),
  KEY `idx_admin_audit_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理端操作审计';
"""

DROP_SCRIPT = """\
-- =============================================================================
-- 云条件单后台管理系统 —— 系统管理模块 回滚脚本
-- -----------------------------------------------------------------------------
-- ⚠️ 危险：会删除下面 8 张 T_Admin* 表**及其全部数据**。
--    本脚本**只删 T_Admin* 前缀的表**，不会碰到任何已有业务表。
--    它存在的意义是「允许重建」，日常不要执行。
--
-- 已核对：qianlong_cloud 库中原本没有任何 T_Admin* 表（建表前自检已通过）。
-- =============================================================================

DROP TABLE IF EXISTS `T_AdminAudit`;
DROP TABLE IF EXISTS `T_AdminLoginLog`;
DROP TABLE IF EXISTS `T_AdminPasswordHistory`;
DROP TABLE IF EXISTS `T_AdminPasswordPolicy`;
DROP TABLE IF EXISTS `T_AdminUserRole`;
DROP TABLE IF EXISTS `T_AdminRolePermission`;
DROP TABLE IF EXISTS `T_AdminUser`;
DROP TABLE IF EXISTS `T_AdminRole`;
"""


def build_schema() -> str:
    return SCHEMA_HEADER + SCHEMA_BODY


# --------------------------------------------------------------------------- #
# 02_data_admin.sql
# --------------------------------------------------------------------------- #

def build_roles() -> tuple[list[list], list[list]]:
    roles = [
        [1, "super_admin", "超级管理员",
         "拥有全部权限（通配符 *）。菜单新增后自动覆盖，无需回填权限点。", 1,
         ago(400 * 86400), ago(60 * 86400)],
        [2, "ops", "运维",
         "日常运维：可查看全部业务页面、启停节点与条件单，但不能删除 Redis 键、不能读任意 SQL、不能改后台账号。",
         1, ago(370 * 86400), ago(50 * 86400)],
        [3, "viewer", "只读观察者",
         "只能看。适合把界面投到大屏或给外部同事核对数据，任何写操作都会被后端拒绝。",
         1, ago(340 * 86400), ago(40 * 86400)],
        [4, "auditor", "审计员",
         "自定义角色：只能看审计与登录日志，不能碰业务页面。", 0,
         ago(45 * 86400), ago(12 * 86400)],
    ]

    # 权限点：先收集 (roleId, permissionKey)，最后统一补自增 id
    pairs: list[tuple[int, str]] = []
    # super_admin：通配符（不逐项回填，菜单新增自动覆盖）
    pairs.append((1, "*"))
    # ops
    ops_menus = [
        "dashboard", "server-trade", "server-datahub", "server-strategy", "server-stream",
        "conditional", "order", "deal", "strategy-log",
        "account-user", "account-sign", "account-log",
        "storage-redis", "storage-mysql", "system-audit",
    ]
    ops_actions = [
        "action:conditional:write", "action:server-trade:toggle",
        "action:storage-mysql:readonly-sql", "action:system-audit:export",
    ]
    for k in [f"menu:{n}" for n in ops_menus] + ops_actions:
        pairs.append((2, k))
    # viewer
    viewer_menus = [
        "dashboard", "server-trade", "server-datahub", "server-strategy", "server-stream",
        "conditional", "order", "deal", "strategy-log",
        "account-user", "account-sign", "account-log",
        "storage-redis", "storage-mysql",
    ]
    for k in [f"menu:{n}" for n in viewer_menus]:
        pairs.append((3, k))
    # auditor
    for k in ["menu:dashboard", "menu:system-audit", "action:system-audit:export"]:
        pairs.append((4, k))

    perms = [[i, role_id, key] for i, (role_id, key) in enumerate(pairs, start=1)]
    return roles, perms


def build_users() -> tuple[list[dict], list[list], list[list]]:
    seeds = [
        # username, displayName, password, roleIds, enabled, locked, failed, mustChange, remark, builtin
        ("admin", "系统管理员", PWD_ADMIN, [1], 1, 0, 0, 0,
         "内置账号，现场唯一的超管。改密后请同步告知运维。", 1),
        ("yangsh", "杨", PWD_OPS, [2], 1, 0, 0, 0,
         "现场运维同学（136 上的部署目录 /home/yangsh/so_test 即此人）。", 0),
        ("zhangguochang", "张国昌", PWD_VIEW, [3], 1, 0, 1, 1,
         "首次登录需改密。账号名与云单账号 300130000461 同属一个客户，但不是一回事（那是 T_User，这是后台账号）。", 0),
        ("audit01", "合规审计", PWD_OPS, [4], 1, 0, 0, 0, "合规审计岗位使用。", 0),
        ("liuyi", "刘工", PWD_OPS, [2], 0, 0, 0, 0,
         "已离职/交接中，账号先停用，不删除（审计要认人）。", 0),
        ("test_tmp", "临时测试", PWD_OPS, [2], 1, 1, 5, 0,
         "连续 5 次密码错误被锁定，需管理员手动解锁。", 0),
    ]

    users: list[dict] = []
    user_roles: list[list] = []
    history: list[list] = []
    uid = 1001
    rid = 1
    hid = 1
    for i, (username, display, pwd, role_ids, enabled, locked, failed, must_change, remark, builtin) in enumerate(seeds):
        created = ago((500 - i * 40) * 86400)
        updated = ago((30 - (i % 6) * 4) * 86400)
        users.append({
            "id": uid,
            "username": username,
            "displayName": display,
            "passwordHash": bcrypt_hash(pwd),
            "enabled": enabled,
            "locked": locked,
            "failedAttempts": failed,
            "mustChangePassword": must_change,
            # ⚠️ 锁定时间必须**在脚本执行那一刻**相对 NOW() 求值，不能用 Python 里
            # 算好的字面量。原因：生成 SQL 的时间与有人执行 SQL 的时间可能差好几天，
            # 而 lockMinutes=30 —— 写死的「30 分钟前」等到执行时早就过期，
            # 后端下次登录会判定「锁定已到期」自动解锁，这条「锁定中」的测试数据就废了。
            # 实测踩过这个坑：`status=locked` 筛出 0 条。
            "lockTime": RawSql("NOW() - INTERVAL 12 MINUTE") if locked else None,
            "passwordUpdateTime": created,
            "lastLoginTime": None if username == "test_tmp" else ago(RAND.randint(120, 86400 * 9)),
            "lastLoginIP": None if username == "test_tmp" else RAND.choice(SAFE_IPS),
            "loginCount": 0 if username == "test_tmp" else RAND.randint(3, 260),
            "builtin": builtin,
            "remark": remark,
            "createdAt": created,
            "updatedAt": updated,
        })
        for r in role_ids:
            user_roles.append([rid, uid, r])
            rid += 1
        # 历史密码：给两条账号塞「更早的」哈希，用来验证 historySize 拦截
        hist_count = 2 if username in ("admin", "yangsh") else 0
        for h in range(hist_count):
            history.append([hid, uid, bcrypt_hash(f"Hist@{username}#{h + 1}"), ago((h + 1) * 90 * 86400)])
            hid += 1
        uid += 1

    return users, user_roles, history


def build_audit_logs() -> list[list]:
    rows: list[list] = []
    doers = ["admin", "yangsh", "audit01"]
    for i in range(220):
        act = RAND.choice(AUDIT_ACTIONS)
        targets = AUDIT_TARGETS.get(act["action"], [""])
        messages = AUDIT_MESSAGES.get(act["action"], ["执行成功"])
        result = "success" if RAND.random() > 0.14 else "failure"
        seconds = RAND.randint(60, 86400 * 45)
        if result == "success":
            message = RAND.choice(messages)
        else:
            bad = [m for m in messages if any(w in m for w in ("拒绝", "超时", "错误", "不存在", "失败"))]
            message = RAND.choice(bad + ["内部错误"])
        rows.append([
            70001 + i,
            RAND.choice(doers),
            act["action"],
            act["label"],
            act["module"],
            act["level"],
            RAND.choice(targets) or None,
            result,
            message,
            RAND.choice(SAFE_IPS),
            RAND.randint(3, 2400),
            ago(seconds),
        ])
    return rows


def build_login_logs() -> list[list]:
    rows: list[list] = []
    users = ["admin", "yangsh", "zhangguochang", "audit01", "liuyi", "test_tmp"]
    for i in range(180):
        username = RAND.choice(users)
        success = 1 if RAND.random() > 0.28 else 0
        seconds = RAND.randint(60, 86400 * 30)
        risky = RAND.random() > 0.86
        rows.append([
            90001 + i,
            username,
            ago(seconds),
            RAND.choice(RISKY_IPS) if risky else RAND.choice(SAFE_IPS),
            RAND.choice(LOGIN_OS),
            success,
            "登录成功" if success else RAND.choice(LOGIN_FAIL_REASONS),
        ])
    return rows


DATA_HEADER_TMPL = """\
-- =============================================================================
-- 云条件单后台管理系统 —— 系统管理模块 测试数据
-- -----------------------------------------------------------------------------
-- 目标库：qianlong_cloud @ 192.168.1.136:3306
-- 执行顺序：先 01_schema_admin.sql，再本文件。
--
-- ⚠️ 本脚本**只写 T_Admin* 前缀的表**：所有 DELETE 都只针对本模块自己的 8 张表，
--      已有业务表（T_Conditional / T_Conditional_his / V_Conditional / T_Order /
--      T_Order_his / T_Trade / T_Trade_his / T_User / T_UserLog / T_StrategyLog）
--      **一行都不会被读写**。
--
-- 测试口令（bcrypt cost=10 哈希已写死在本文件里；明文仅用于联调，生产必须改掉）：
--     admin          / {pwd_admin}   超级管理员（通配符 *，全部权限）
--     yangsh         / {pwd_ops}   运维（可启停，不能删键 / 改账号 / 读任意 SQL）
--     zhangguochang  / {pwd_view}   只读观察者（写操作全点不动，且首次登录需改密）
--     audit01        / {pwd_ops}   审计员（自定义角色，只能看审计）
--     liuyi          / {pwd_ops}   **故意登不进去**：账号已停用
--     test_tmp       / {pwd_ops}   **故意登不进去**：连续失败 5 次已锁定
--   口令与前端 src/api/mock/system.ts 的演示口令一致，便于后端联调时对照。
--   现场真实口令**绝不入库、绝不入仓**。
--
-- 数据规模：角色 4 / 权限点 {perm_count} / 账号 {user_count} / 账号-角色 {ur_count}
--           审计 {audit_count} 行（跨最近 45 天）/ 登录日志 {login_count} 行（跨最近 30 天）
--
-- 密码策略：长度 ≥ 6，大小写/数字/符号均不要求（2026-10-12 按需求方要求放宽）。
--           锁定（5 次 / 30 分钟）与历史密码（最近 5 次不重复）保留不变。
--           下面这些测试口令都属于「长度够 + 不在历史里」，可直接使用。
-- =============================================================================

USE `qianlong_cloud`;

-- 关闭外键检查仅为本脚本自身的写入顺序服务；本模块未建外键约束，保留是为了容错。
SET FOREIGN_KEY_CHECKS = 0;
SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 幂等：只清空本模块自己的数据（8 张 T_Admin* 表），随后整体重灌，
--       这样脚本可以反复执行而不会累积重复行。
--       注意这里**没有 TRUNCATE**：TRUNCATE 是 DDL，会隐式提交事务，
--       一旦中途失败就留下半套数据；DELETE 全部包在事务里更可控。
-- -----------------------------------------------------------------------------
DELETE FROM `T_AdminAudit`;
DELETE FROM `T_AdminLoginLog`;
DELETE FROM `T_AdminPasswordHistory`;
DELETE FROM `T_AdminUserRole`;
DELETE FROM `T_AdminRolePermission`;
DELETE FROM `T_AdminUser`;
DELETE FROM `T_AdminRole`;
DELETE FROM `T_AdminPasswordPolicy`;

-- -----------------------------------------------------------------------------
-- 1. 密码策略与登录失败锁定策略（单行，id 恒为 1）
--    放宽后的口径：仅要求长度 ≥ 6；大小写/数字/符号一律不要求。
--    锁定（5 次 / 30 分钟）与历史密码（最近 5 次不重复）保留不变。
-- -----------------------------------------------------------------------------
INSERT INTO `T_AdminPasswordPolicy`
  (`id`, `minLength`, `requireUpper`, `requireLower`, `requireDigit`, `requireSymbol`,
   `maxFailedAttempts`, `lockMinutes`, `expireDays`, `forceChangeOnFirstLogin`, `historySize`)
VALUES
  (1, 6, 0, 0, 0, 0, 5, 30, 90, 1, 5);

-- -----------------------------------------------------------------------------
-- 2. 角色（3 个内置 + 1 个自定义）
-- -----------------------------------------------------------------------------
"""


def build_data(roles, perms, users, user_roles, history, audit_rows, login_rows) -> str:
    parts: list[str] = [
        DATA_HEADER_TMPL.format(
            pwd_admin=PWD_ADMIN, pwd_ops=PWD_OPS, pwd_view=PWD_VIEW,
            perm_count=len(perms), user_count=len(users), ur_count=len(user_roles),
            audit_count=len(audit_rows), login_count=len(login_rows),
        )
    ]
    parts.append(rows_to_insert(
        "T_AdminRole", ["id", "code", "name", "description", "builtin", "createdAt", "updatedAt"], roles))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 3. 角色权限点
        --    权限键与前端 src/config/permissions.ts 完全同源；super_admin 只需一条 `*`。
        -- -----------------------------------------------------------------------------
        """))
    parts.append(rows_to_insert("T_AdminRolePermission", ["id", "roleId", "permissionKey"], perms))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 4. 管理账号
        --    passwordHash 为 BCrypt（$2a$10$...）哈希，只写不读。
        --    liuyi 已停用、test_tmp 已锁定并有 5 次失败，用来验证登录页的错误分支。
        -- -----------------------------------------------------------------------------
        """))
    user_columns = [
        "id", "username", "displayName", "passwordHash", "enabled", "locked",
        "failedAttempts", "mustChangePassword", "lockTime", "passwordUpdateTime", "lastLoginTime",
        "lastLoginIP", "loginCount", "builtin", "remark", "createdAt", "updatedAt",
    ]
    parts.append(rows_to_insert(
        "T_AdminUser", user_columns, [[u[c] for c in user_columns] for u in users]))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 5. 账号-角色关系
        -- -----------------------------------------------------------------------------
        """))
    parts.append(rows_to_insert("T_AdminUserRole", ["id", "userId", "roleId"], user_roles))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 6. 历史密码哈希（支撑策略里的 historySize = 5）
        --    注意：这里只放了 2 条「更早的」哈希，用于验证「新密码不得与历史重复」这条规则。
        -- -----------------------------------------------------------------------------
        """))
    parts.append(rows_to_insert(
        "T_AdminPasswordHistory", ["id", "userId", "passwordHash", "createdAt"], history))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 7. 管理端登录日志（180 行，跨最近 30 天）
        --    含失败记录与外部可疑 IP，供「失败率 / 失败来源 IP Top」两个图表聚合。
        -- -----------------------------------------------------------------------------
        """))
    parts.append(rows_to_insert(
        "T_AdminLoginLog",
        ["id", "username", "loginTime", "loginIP", "loginOS", "success", "info"], login_rows))
    parts.append(textwrap.dedent("""
        -- -----------------------------------------------------------------------------
        -- 8. 操作审计（220 行，跨最近 45 天）
        --    action 与权限键同源；另有 system:login / system:connection 两个登记型动作。
        --    含失败记录，用于验证「失败操作」统计与详情抽屉。
        -- -----------------------------------------------------------------------------
        """))
    parts.append(rows_to_insert(
        "T_AdminAudit",
        ["id", "operator", "action", "actionLabel", "module", "level", "target", "result",
         "message", "ip", "costMs", "createdAt"], audit_rows))
    parts.append(textwrap.dedent("""
        SET FOREIGN_KEY_CHECKS = 1;

        -- -----------------------------------------------------------------------------
        -- 自检：执行完上面的语句后，可用下面的查询核对（期望：8 行，且行数如下注释）
        -- -----------------------------------------------------------------------------
        -- SELECT 'T_AdminRole' AS t, COUNT(*) FROM T_AdminRole
        -- UNION ALL SELECT 'T_AdminRolePermission', COUNT(*) FROM T_AdminRolePermission
        -- UNION ALL SELECT 'T_AdminUser', COUNT(*) FROM T_AdminUser
        -- UNION ALL SELECT 'T_AdminUserRole', COUNT(*) FROM T_AdminUserRole
        -- UNION ALL SELECT 'T_AdminPasswordPolicy', COUNT(*) FROM T_AdminPasswordPolicy
        -- UNION ALL SELECT 'T_AdminPasswordHistory', COUNT(*) FROM T_AdminPasswordHistory
        -- UNION ALL SELECT 'T_AdminLoginLog', COUNT(*) FROM T_AdminLoginLog
        -- UNION ALL SELECT 'T_AdminAudit', COUNT(*) FROM T_AdminAudit;
        """))
    return "\n".join(parts)


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)

    roles, perms = build_roles()
    users, user_roles, history = build_users()
    audit_rows = build_audit_logs()
    login_rows = build_login_logs()

    schema_path = os.path.join(OUT_DIR, "01_schema_admin.sql")
    data_path = os.path.join(OUT_DIR, "02_data_admin.sql")
    drop_path = os.path.join(OUT_DIR, "00_drop_admin.sql")

    with open(schema_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(build_schema())
    with open(drop_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(DROP_SCRIPT)
    with open(data_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(build_data(roles, perms, users, user_roles, history, audit_rows, login_rows))

    for p in (drop_path, schema_path, data_path):
        print(f"written {p}  ({os.path.getsize(p):,} bytes)")
    print(f"users={len(users)} perms={len(perms)} userRoles={len(user_roles)} "
          f"audit={len(audit_rows)} login={len(login_rows)} history={len(history)}")


if __name__ == "__main__":
    main()
