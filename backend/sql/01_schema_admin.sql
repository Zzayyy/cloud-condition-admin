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
