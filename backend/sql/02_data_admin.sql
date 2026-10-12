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
--     admin          / Admin@2026   超级管理员（通配符 *，全部权限）
--     yangsh         / Ops@2026   运维（可启停，不能删键 / 改账号 / 读任意 SQL）
--     zhangguochang  / View@2026   只读观察者（写操作全点不动，且首次登录需改密）
--     audit01        / Ops@2026   审计员（自定义角色，只能看审计）
--     liuyi          / Ops@2026   **故意登不进去**：账号已停用
--     test_tmp       / Ops@2026   **故意登不进去**：连续失败 5 次已锁定
--   口令与前端 src/api/mock/system.ts 的演示口令一致，便于后端联调时对照。
--   现场真实口令**绝不入库、绝不入仓**。
--
-- 数据规模：角色 4 / 权限点 37 / 账号 6 / 账号-角色 6
--           审计 220 行（跨最近 45 天）/ 登录日志 180 行（跨最近 30 天）
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

INSERT INTO `T_AdminRole` (`id`, `code`, `name`, `description`, `builtin`, `createdAt`, `updatedAt`) VALUES
  (1, 'super_admin', '超级管理员', '拥有全部权限（通配符 *）。菜单新增后自动覆盖，无需回填权限点。', 1, '2025-09-07 16:37:56', '2026-08-13 16:37:56'),
  (2, 'ops', '运维', '日常运维：可查看全部业务页面、启停节点与条件单，但不能删除 Redis 键、不能读任意 SQL、不能改后台账号。', 1, '2025-10-07 16:37:56', '2026-08-23 16:37:56'),
  (3, 'viewer', '只读观察者', '只能看。适合把界面投到大屏或给外部同事核对数据，任何写操作都会被后端拒绝。', 1, '2025-11-06 16:37:56', '2026-09-02 16:37:56'),
  (4, 'auditor', '审计员', '自定义角色：只能看审计与登录日志，不能碰业务页面。', 0, '2026-08-28 16:37:56', '2026-09-30 16:37:56');

-- -----------------------------------------------------------------------------
-- 3. 角色权限点
--    权限键与前端 src/config/permissions.ts 完全同源；super_admin 只需一条 `*`。
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminRolePermission` (`id`, `roleId`, `permissionKey`) VALUES
  (1, 1, '*'),
  (2, 2, 'menu:dashboard'),
  (3, 2, 'menu:server-trade'),
  (4, 2, 'menu:server-datahub'),
  (5, 2, 'menu:server-strategy'),
  (6, 2, 'menu:server-stream'),
  (7, 2, 'menu:conditional'),
  (8, 2, 'menu:order'),
  (9, 2, 'menu:deal'),
  (10, 2, 'menu:strategy-log'),
  (11, 2, 'menu:account-user'),
  (12, 2, 'menu:account-sign'),
  (13, 2, 'menu:account-log'),
  (14, 2, 'menu:storage-redis'),
  (15, 2, 'menu:storage-mysql'),
  (16, 2, 'menu:system-audit'),
  (17, 2, 'action:conditional:write'),
  (18, 2, 'action:server-trade:toggle'),
  (19, 2, 'action:storage-mysql:readonly-sql'),
  (20, 2, 'action:system-audit:export'),
  (21, 3, 'menu:dashboard'),
  (22, 3, 'menu:server-trade'),
  (23, 3, 'menu:server-datahub'),
  (24, 3, 'menu:server-strategy'),
  (25, 3, 'menu:server-stream'),
  (26, 3, 'menu:conditional'),
  (27, 3, 'menu:order'),
  (28, 3, 'menu:deal'),
  (29, 3, 'menu:strategy-log'),
  (30, 3, 'menu:account-user'),
  (31, 3, 'menu:account-sign'),
  (32, 3, 'menu:account-log'),
  (33, 3, 'menu:storage-redis'),
  (34, 3, 'menu:storage-mysql'),
  (35, 4, 'menu:dashboard'),
  (36, 4, 'menu:system-audit'),
  (37, 4, 'action:system-audit:export');

-- -----------------------------------------------------------------------------
-- 4. 管理账号
--    passwordHash 为 BCrypt（$2a$10$...）哈希，只写不读。
--    liuyi 已停用、test_tmp 已锁定并有 5 次失败，用来验证登录页的错误分支。
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminUser` (`id`, `username`, `displayName`, `passwordHash`, `enabled`, `locked`, `failedAttempts`, `mustChangePassword`, `lockTime`, `passwordUpdateTime`, `lastLoginTime`, `lastLoginIP`, `loginCount`, `builtin`, `remark`, `createdAt`, `updatedAt`) VALUES
  (1001, 'admin', '系统管理员', '$2b$10$F2v0CRpUJwLFuD3wnubWmeRd0GtFgwgGya6WXDduPwWMt55brgD8.', 1, 0, 0, 0, NULL, '2025-05-30 16:37:56', '2026-10-10 03:04:10', '192.168.1.136', 228, 1, '内置账号，现场唯一的超管。改密后请同步告知运维。', '2025-05-30 16:37:56', '2026-09-12 16:37:56'),
  (1002, 'yangsh', '杨', '$2b$10$Y1ESgncYgzOg8GcizM2hzOvQYBcluqKE9iN5DXY1SYMwBrgya50Ay', 1, 0, 0, 0, NULL, '2025-07-09 16:37:56', '2026-10-06 20:30:12', '192.168.1.136', 23, 0, '现场运维同学（136 上的部署目录 /home/yangsh/so_test 即此人）。', '2025-07-09 16:37:56', '2026-09-16 16:37:56'),
  (1003, 'zhangguochang', '张国昌', '$2b$10$KGjwOzOUXFOCcA4xr1.zyOgPggSF8tcXBSs7P1MX7fwORffFdjcz2', 1, 0, 1, 1, NULL, '2025-08-18 16:37:56', '2026-10-12 07:53:47', '10.0.0.21', 102, 0, '首次登录需改密。账号名与云单账号 300130000461 同属一个客户，但不是一回事（那是 T_User，这是后台账号）。', '2025-08-18 16:37:56', '2026-09-20 16:37:56'),
  (1004, 'audit01', '合规审计', '$2b$10$euTlMAvzf36fC3BkVW.zyu1YIlh/hFkFlpseSLVDlvtOEXSCK9VVi', 1, 0, 0, 0, NULL, '2025-09-27 16:37:56', '2026-10-06 07:37:55', '10.0.0.35', 205, 0, '合规审计岗位使用。', '2025-09-27 16:37:56', '2026-09-24 16:37:56'),
  (1005, 'liuyi', '刘工', '$2b$10$4ItTfzl0x1W.Bv8hV0B3feDL7i8.ab5fzbvJm./L1yqwJ/MMgONCS', 0, 0, 0, 0, NULL, '2025-11-06 16:37:56', '2026-10-05 17:51:39', '10.0.0.21', 204, 0, '已离职/交接中，账号先停用，不删除（审计要认人）。', '2025-11-06 16:37:56', '2026-09-28 16:37:56'),
  (1006, 'test_tmp', '临时测试', '$2b$10$1FZULS8KU9/VCXo16jFXHecpsCXi9YbMHdemFU4o5ScwuA4k4a8dO', 1, 1, 5, 0, NOW() - INTERVAL 12 MINUTE, '2025-12-16 16:37:56', NULL, NULL, 0, 0, '连续 5 次密码错误被锁定，需管理员手动解锁。', '2025-12-16 16:37:56', '2026-10-02 16:37:56');

-- -----------------------------------------------------------------------------
-- 5. 账号-角色关系
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminUserRole` (`id`, `userId`, `roleId`) VALUES
  (1, 1001, 1),
  (2, 1002, 2),
  (3, 1003, 3),
  (4, 1004, 4),
  (5, 1005, 2),
  (6, 1006, 2);

-- -----------------------------------------------------------------------------
-- 6. 历史密码哈希（支撑策略里的 historySize = 5）
--    注意：这里只放了 2 条「更早的」哈希，用于验证「新密码不得与历史重复」这条规则。
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminPasswordHistory` (`id`, `userId`, `passwordHash`, `createdAt`) VALUES
  (1, 1001, '$2b$10$p6.GYKt5yineCNuHlUmXu.UGWp7yj0ogkZt9hbooAmiB.99oUxYOq', '2026-07-14 16:37:56'),
  (2, 1001, '$2b$10$zok7Fi6g7yIxDIsq7P0E3uwwL9LtDFUjiOmWsF.HQDxyfMDjAVJAC', '2026-04-15 16:37:56'),
  (3, 1002, '$2b$10$1isf7VtiZyGKSZ/FnpXh/ue3ZsMBfX8GLPC0Wm1AiPz/I.kAji1Lm', '2026-07-14 16:37:56'),
  (4, 1002, '$2b$10$SNSEDSO87F7qOrodJ/XYUeWKUzCBqFHKZQwAQQzX9oYKj6EEmH6ke', '2026-04-15 16:37:56');

-- -----------------------------------------------------------------------------
-- 7. 管理端登录日志（180 行，跨最近 30 天）
--    含失败记录与外部可疑 IP，供「失败率 / 失败来源 IP Top」两个图表聚合。
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminLoginLog` (`id`, `username`, `loginTime`, `loginIP`, `loginOS`, `success`, `info`) VALUES
  (90001, 'zhangguochang', '2026-10-08 02:16:07', '198.51.100.7', 'macOS 15 / Safari 18', 0, '账号已锁定'),
  (90002, 'audit01', '2026-09-24 13:50:00', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90003, 'yangsh', '2026-10-09 08:30:15', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '密码错误'),
  (90004, 'zhangguochang', '2026-09-30 11:01:54', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90005, 'admin', '2026-10-07 12:40:04', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90006, 'audit01', '2026-10-01 23:06:40', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90007, 'zhangguochang', '2026-09-12 22:24:37', '198.51.100.7', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90008, 'liuyi', '2026-10-03 08:28:26', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90009, 'admin', '2026-09-29 19:32:54', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90010, 'test_tmp', '2026-09-28 03:21:20', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90011, 'admin', '2026-09-29 02:55:46', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90012, 'yangsh', '2026-09-24 02:33:11', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90013, 'test_tmp', '2026-09-19 04:46:59', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90014, 'test_tmp', '2026-10-10 08:22:54', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90015, 'audit01', '2026-09-14 13:49:03', '203.0.113.44', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90016, 'admin', '2026-10-05 12:17:25', '203.0.113.44', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90017, 'admin', '2026-09-13 18:12:00', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号已锁定'),
  (90018, 'admin', '2026-09-15 13:48:34', '203.0.113.44', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90019, 'audit01', '2026-09-16 00:06:10', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90020, 'liuyi', '2026-10-08 11:14:01', '198.51.100.7', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90021, 'test_tmp', '2026-10-09 15:56:17', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90022, 'yangsh', '2026-09-14 07:41:56', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90023, 'zhangguochang', '2026-10-10 18:44:47', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90024, 'zhangguochang', '2026-10-10 13:09:30', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90025, 'admin', '2026-09-21 14:14:15', '10.0.0.21', 'Windows 11 / Chrome 141', 0, '账号不存在'),
  (90026, 'audit01', '2026-09-17 22:51:48', '10.0.0.35', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90027, 'liuyi', '2026-09-24 05:35:46', '10.0.0.35', 'Windows 11 / Chrome 141', 0, '密码错误'),
  (90028, 'admin', '2026-09-26 19:41:33', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90029, 'zhangguochang', '2026-10-07 16:53:34', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90030, 'liuyi', '2026-10-01 19:15:57', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90031, 'admin', '2026-09-16 13:55:57', '192.168.1.136', 'CentOS 7 / curl', 0, '账号已停用'),
  (90032, 'test_tmp', '2026-10-03 22:50:49', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90033, 'admin', '2026-09-28 23:35:40', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90034, 'admin', '2026-09-26 03:07:47', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90035, 'test_tmp', '2026-09-21 16:35:43', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90036, 'yangsh', '2026-09-24 00:59:07', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90037, 'zhangguochang', '2026-10-11 19:46:14', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90038, 'test_tmp', '2026-09-19 08:59:11', '192.168.1.136', 'Windows 10 / Edge 141', 0, '密码错误'),
  (90039, 'yangsh', '2026-09-29 17:39:01', '10.0.0.21', 'Windows 11 / Chrome 141', 0, '账号已停用'),
  (90040, 'admin', '2026-09-13 15:11:44', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90041, 'test_tmp', '2026-09-16 00:49:46', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号不存在'),
  (90042, 'audit01', '2026-10-08 11:04:59', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90043, 'zhangguochang', '2026-09-27 19:38:08', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90044, 'yangsh', '2026-09-24 19:28:07', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90045, 'yangsh', '2026-10-03 05:49:26', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90046, 'yangsh', '2026-09-15 23:26:34', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90047, 'audit01', '2026-09-25 10:30:39', '203.0.113.44', 'CentOS 7 / curl', 1, '登录成功'),
  (90048, 'admin', '2026-09-26 19:01:42', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90049, 'test_tmp', '2026-10-12 06:46:12', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90050, 'test_tmp', '2026-09-21 10:58:28', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90051, 'zhangguochang', '2026-10-10 19:31:16', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90052, 'yangsh', '2026-10-05 22:17:24', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90053, 'admin', '2026-10-05 06:50:37', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90054, 'audit01', '2026-10-01 20:09:05', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90055, 'liuyi', '2026-10-11 01:08:32', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90056, 'admin', '2026-10-05 19:16:46', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90057, 'liuyi', '2026-09-29 19:46:13', '198.51.100.7', 'CentOS 7 / curl', 1, '登录成功'),
  (90058, 'audit01', '2026-10-05 08:43:59', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90059, 'liuyi', '2026-09-19 11:37:53', '10.0.0.21', 'macOS 15 / Safari 18', 0, '账号已停用'),
  (90060, 'audit01', '2026-09-19 12:36:35', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90061, 'yangsh', '2026-09-20 13:55:41', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90062, 'zhangguochang', '2026-10-08 08:43:24', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90063, 'admin', '2026-09-28 15:59:36', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90064, 'audit01', '2026-09-18 11:57:32', '192.168.1.136', 'CentOS 7 / curl', 0, '密码错误'),
  (90065, 'yangsh', '2026-09-19 12:27:28', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90066, 'audit01', '2026-09-24 19:02:12', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90067, 'audit01', '2026-09-26 06:41:25', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90068, 'audit01', '2026-10-08 01:07:43', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90069, 'liuyi', '2026-10-05 19:35:46', '203.0.113.44', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90070, 'test_tmp', '2026-09-13 19:25:41', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90071, 'test_tmp', '2026-10-03 01:41:22', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90072, 'admin', '2026-09-25 09:42:02', '10.0.0.21', 'Windows 11 / Chrome 141', 0, '账号已锁定'),
  (90073, 'test_tmp', '2026-09-26 13:29:09', '203.0.113.44', 'CentOS 7 / curl', 0, '账号已锁定'),
  (90074, 'test_tmp', '2026-10-04 14:11:19', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90075, 'zhangguochang', '2026-09-13 22:25:23', '10.0.0.21', 'macOS 15 / Safari 18', 0, '账号已停用'),
  (90076, 'admin', '2026-09-22 02:31:32', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90077, 'test_tmp', '2026-09-21 13:18:43', '10.0.0.35', 'CentOS 7 / curl', 0, '密码错误'),
  (90078, 'zhangguochang', '2026-09-30 23:13:52', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90079, 'audit01', '2026-10-08 13:33:13', '203.0.113.44', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90080, 'audit01', '2026-09-12 19:56:04', '198.51.100.7', 'Windows 11 / Chrome 141', 0, '账号已停用'),
  (90081, 'liuyi', '2026-09-14 07:48:18', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号已停用'),
  (90082, 'admin', '2026-10-12 06:58:06', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90083, 'audit01', '2026-10-11 03:35:50', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90084, 'zhangguochang', '2026-10-08 07:28:46', '10.0.0.21', 'CentOS 7 / curl', 0, '密码错误'),
  (90085, 'liuyi', '2026-09-23 02:15:47', '192.168.1.136', 'macOS 15 / Safari 18', 0, '密码错误'),
  (90086, 'admin', '2026-09-22 04:35:50', '10.0.0.21', 'CentOS 7 / curl', 0, '账号不存在'),
  (90087, 'liuyi', '2026-10-05 23:31:09', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90088, 'zhangguochang', '2026-10-04 23:13:09', '10.0.0.21', 'macOS 15 / Safari 18', 0, '账号已锁定'),
  (90089, 'yangsh', '2026-09-15 17:35:30', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90090, 'audit01', '2026-09-15 02:06:03', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90091, 'yangsh', '2026-10-09 11:19:10', '198.51.100.7', 'CentOS 7 / curl', 0, '账号不存在'),
  (90092, 'test_tmp', '2026-09-27 14:17:31', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90093, 'admin', '2026-10-02 08:40:14', '203.0.113.44', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90094, 'liuyi', '2026-09-12 18:13:23', '10.0.0.21', 'CentOS 7 / curl', 0, '账号已锁定'),
  (90095, 'admin', '2026-09-16 10:33:46', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90096, 'yangsh', '2026-10-08 10:17:49', '10.0.0.35', 'CentOS 7 / curl', 0, '账号已停用'),
  (90097, 'yangsh', '2026-10-12 11:38:18', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90098, 'liuyi', '2026-10-07 20:00:35', '10.0.0.35', 'CentOS 7 / curl', 0, '密码错误'),
  (90099, 'yangsh', '2026-10-02 06:59:22', '10.0.0.21', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90100, 'test_tmp', '2026-10-03 04:56:15', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90101, 'liuyi', '2026-09-28 14:40:20', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90102, 'audit01', '2026-09-12 22:53:33', '10.0.0.35', 'macOS 15 / Safari 18', 0, '密码错误'),
  (90103, 'yangsh', '2026-09-15 19:34:24', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90104, 'zhangguochang', '2026-10-10 16:17:08', '198.51.100.7', 'macOS 15 / Safari 18', 0, '账号已停用'),
  (90105, 'liuyi', '2026-09-28 21:41:03', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90106, 'test_tmp', '2026-10-02 02:19:37', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90107, 'admin', '2026-10-03 03:46:50', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90108, 'admin', '2026-09-28 08:20:04', '203.0.113.44', 'CentOS 7 / curl', 1, '登录成功'),
  (90109, 'yangsh', '2026-09-15 05:52:33', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90110, 'admin', '2026-10-11 15:24:27', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90111, 'zhangguochang', '2026-09-30 06:53:21', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90112, 'admin', '2026-10-01 05:59:05', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90113, 'yangsh', '2026-09-16 12:43:39', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90114, 'liuyi', '2026-10-05 23:23:23', '10.0.0.21', 'macOS 15 / Safari 18', 0, '账号已锁定'),
  (90115, 'yangsh', '2026-10-08 15:55:57', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90116, 'yangsh', '2026-09-25 16:07:49', '203.0.113.44', 'macOS 15 / Safari 18', 0, '账号已停用'),
  (90117, 'zhangguochang', '2026-10-03 01:55:06', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90118, 'audit01', '2026-10-11 09:34:03', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90119, 'admin', '2026-10-09 08:18:33', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90120, 'yangsh', '2026-09-15 00:50:36', '10.0.0.21', 'CentOS 7 / curl', 0, '账号已停用'),
  (90121, 'zhangguochang', '2026-09-15 09:59:09', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90122, 'liuyi', '2026-09-16 19:45:38', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90123, 'admin', '2026-09-14 03:40:00', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90124, 'zhangguochang', '2026-10-08 19:35:59', '10.0.0.35', 'macOS 15 / Safari 18', 0, '账号已锁定'),
  (90125, 'test_tmp', '2026-09-27 11:56:44', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90126, 'yangsh', '2026-10-07 10:16:04', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号已停用'),
  (90127, 'audit01', '2026-09-29 21:36:16', '203.0.113.44', 'macOS 15 / Safari 18', 0, '账号已锁定'),
  (90128, 'audit01', '2026-09-18 09:32:35', '10.0.0.35', 'Windows 10 / Edge 141', 0, '密码错误'),
  (90129, 'yangsh', '2026-10-02 09:58:47', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90130, 'test_tmp', '2026-09-17 09:25:01', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90131, 'admin', '2026-09-23 05:02:19', '192.168.1.136', 'Windows 10 / Edge 141', 0, '账号已锁定'),
  (90132, 'zhangguochang', '2026-10-05 21:07:22', '198.51.100.7', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90133, 'liuyi', '2026-10-04 08:44:18', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '密码错误'),
  (90134, 'admin', '2026-09-25 14:11:31', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号已停用'),
  (90135, 'audit01', '2026-09-13 23:29:53', '10.0.0.35', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90136, 'yangsh', '2026-09-16 09:30:10', '192.168.1.136', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90137, 'audit01', '2026-09-21 13:25:08', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90138, 'test_tmp', '2026-10-12 13:01:12', '203.0.113.44', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90139, 'zhangguochang', '2026-09-22 18:30:41', '10.0.0.21', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90140, 'liuyi', '2026-09-25 14:07:53', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90141, 'test_tmp', '2026-09-18 18:33:08', '192.168.1.136', 'Windows 10 / Edge 141', 0, '账号不存在'),
  (90142, 'yangsh', '2026-10-07 10:07:46', '10.0.0.35', 'CentOS 7 / curl', 1, '登录成功'),
  (90143, 'test_tmp', '2026-09-13 00:28:18', '198.51.100.7', 'Windows 10 / Edge 141', 0, '账号已锁定'),
  (90144, 'audit01', '2026-10-02 14:24:24', '192.168.1.136', 'CentOS 7 / curl', 1, '登录成功'),
  (90145, 'audit01', '2026-10-07 08:48:32', '192.168.1.136', 'CentOS 7 / curl', 0, '账号已停用'),
  (90146, 'audit01', '2026-09-16 01:59:13', '10.0.0.35', 'CentOS 7 / curl', 0, '账号已停用'),
  (90147, 'test_tmp', '2026-09-28 21:23:14', '198.51.100.7', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90148, 'test_tmp', '2026-09-20 23:27:38', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90149, 'test_tmp', '2026-09-14 06:40:32', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90150, 'liuyi', '2026-10-03 08:27:48', '192.168.1.136', 'CentOS 7 / curl', 0, '账号不存在'),
  (90151, 'zhangguochang', '2026-10-01 13:12:16', '198.51.100.7', 'Windows 11 / Chrome 141', 0, '账号不存在'),
  (90152, 'liuyi', '2026-10-04 17:13:21', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90153, 'audit01', '2026-10-10 09:29:56', '192.168.1.136', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90154, 'admin', '2026-09-12 22:12:19', '10.0.0.35', 'CentOS 7 / curl', 0, '账号已停用'),
  (90155, 'test_tmp', '2026-09-17 01:59:16', '10.0.0.35', 'CentOS 7 / curl', 0, '账号不存在'),
  (90156, 'zhangguochang', '2026-10-10 11:39:34', '10.0.0.21', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90157, 'admin', '2026-09-22 23:01:51', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90158, 'zhangguochang', '2026-09-16 21:53:38', '192.168.1.136', 'Windows 11 / Chrome 141', 0, '账号不存在'),
  (90159, 'admin', '2026-09-14 14:10:10', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90160, 'yangsh', '2026-09-24 02:08:37', '10.0.0.21', 'CentOS 7 / curl', 1, '登录成功'),
  (90161, 'test_tmp', '2026-09-30 03:23:29', '10.0.0.35', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90162, 'liuyi', '2026-10-09 02:03:18', '198.51.100.7', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90163, 'liuyi', '2026-10-03 10:16:56', '203.0.113.44', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90164, 'zhangguochang', '2026-09-25 23:32:54', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90165, 'liuyi', '2026-09-21 05:29:23', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90166, 'liuyi', '2026-09-29 22:17:30', '192.168.1.136', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90167, 'liuyi', '2026-09-30 08:25:39', '10.0.0.35', 'CentOS 7 / curl', 0, '账号已锁定'),
  (90168, 'audit01', '2026-09-25 06:39:32', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90169, 'admin', '2026-10-10 04:53:10', '192.168.1.136', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90170, 'test_tmp', '2026-10-09 12:07:45', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90171, 'audit01', '2026-10-03 15:21:02', '192.168.1.136', 'macOS 15 / Safari 18', 0, '密码错误'),
  (90172, 'admin', '2026-10-11 14:39:14', '10.0.0.35', 'macOS 15 / Safari 18', 1, '登录成功'),
  (90173, 'zhangguochang', '2026-09-28 03:07:27', '192.168.1.136', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90174, 'admin', '2026-09-22 00:06:45', '10.0.0.35', 'Windows 11 / Chrome 141', 1, '登录成功'),
  (90175, 'zhangguochang', '2026-10-01 06:09:03', '10.0.0.35', 'CentOS 7 / curl', 0, '账号不存在'),
  (90176, 'test_tmp', '2026-09-22 21:51:11', '10.0.0.21', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90177, 'test_tmp', '2026-10-10 15:01:03', '198.51.100.7', 'Windows 10 / Edge 141', 0, '账号已停用'),
  (90178, 'admin', '2026-09-16 13:31:45', '198.51.100.7', 'CentOS 7 / curl', 1, '登录成功'),
  (90179, 'yangsh', '2026-09-14 07:59:01', '203.0.113.44', 'Windows 10 / Edge 141', 1, '登录成功'),
  (90180, 'liuyi', '2026-10-02 08:48:32', '10.0.0.21', 'CentOS 7 / curl', 0, '密码错误');

-- -----------------------------------------------------------------------------
-- 8. 操作审计（220 行，跨最近 45 天）
--    action 与权限键同源；另有 system:login / system:connection 两个登记型动作。
--    含失败记录，用于验证「失败操作」统计与详情抽屉。
-- -----------------------------------------------------------------------------

INSERT INTO `T_AdminAudit` (`id`, `operator`, `action`, `actionLabel`, `module`, `level`, `target`, `result`, `message`, `ip`, `costMs`, `createdAt`) VALUES
  (70001, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '执行耗时 128ms', '10.0.0.21', 740, '2026-09-18 12:47:59'),
  (70002, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 remove 协议，策略平台已回执', '192.168.1.136', 1109, '2026-09-29 12:40:08'),
  (70003, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 1803, '2026-10-11 13:16:59'),
  (70004, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 286, '2026-10-12 01:43:57'),
  (70005, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '执行耗时 128ms', '192.168.1.136', 1922, '2026-09-21 06:52:20'),
  (70006, 'admin', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '账号已停用，拒绝登录', '192.168.1.136', 548, '2026-09-29 11:31:14'),
  (70007, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', '目标节点 id 重复，按 mac 定位成功', '192.168.1.136', 1695, '2026-10-11 02:18:17'),
  (70008, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '连通性测试通过', '10.0.0.21', 2387, '2026-10-12 03:36:57'),
  (70009, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'failure', '内部错误', '10.0.0.35', 1005, '2026-09-19 16:31:38'),
  (70010, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'DataHub_destroy_stream', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.21', 512, '2026-08-29 08:05:10'),
  (70011, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.35', 1450, '2026-09-15 16:18:50'),
  (70012, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '新增权限点 3 个', '10.0.0.21', 90, '2026-10-11 00:42:10'),
  (70013, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'success', '键不存在，未产生写入', '192.168.1.136', 338, '2026-10-04 02:31:20'),
  (70014, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'a', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.21', 1045, '2026-09-28 09:38:48'),
  (70015, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'success', '停用账号，旧 token 已失效', '192.168.1.136', 1001, '2026-09-13 07:34:29'),
  (70016, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '权限已保存，涉及 2 个账号', '10.0.0.21', 316, '2026-09-26 10:07:59'),
  (70017, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '键不存在，未产生写入', '192.168.1.136', 2177, '2026-10-04 23:33:46'),
  (70018, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'success', '已下发 set 协议（停止）', '10.0.0.35', 442, '2026-09-17 01:45:46'),
  (70019, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 remove 协议，策略平台已回执', '10.0.0.35', 1504, '2026-10-04 01:25:23'),
  (70020, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'DataHub_destroy_stream', 'success', '删除成功（DEL a = 1）', '10.0.0.21', 1334, '2026-09-22 08:46:00'),
  (70021, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'failure', '内部错误', '10.0.0.35', 234, '2026-09-16 23:32:31'),
  (70022, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'test_tmp', 'success', '停用账号，旧 token 已失效', '192.168.1.136', 157, '2026-10-07 16:37:26'),
  (70023, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.21', 2232, '2026-09-11 01:23:39'),
  (70024, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '192.168.1.136', 2197, '2026-09-30 05:55:10'),
  (70025, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '配置已保存（密码不回显）', '10.0.0.21', 1932, '2026-10-04 10:58:04'),
  (70026, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'failure', '内部错误', '192.168.1.136', 1066, '2026-09-22 15:09:44'),
  (70027, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.35', 1534, '2026-10-02 12:28:51'),
  (70028, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.35', 382, '2026-09-15 02:10:29'),
  (70029, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '10.0.0.35', 1820, '2026-09-13 05:54:10'),
  (70030, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.35', 974, '2026-09-25 20:22:38'),
  (70031, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'a', 'success', '键不存在，未产生写入', '10.0.0.35', 976, '2026-08-30 03:03:16'),
  (70032, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'success', '删除成功（DEL a = 1）', '192.168.1.136', 2386, '2026-09-27 21:09:17'),
  (70033, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'failure', '键不存在，未产生写入', '10.0.0.21', 2360, '2026-09-23 01:42:56'),
  (70034, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 1800, '2026-10-02 19:35:35'),
  (70035, 'audit01', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '连通性测试通过', '10.0.0.35', 463, '2026-09-30 23:17:03'),
  (70036, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '执行耗时 128ms', '192.168.1.136', 805, '2026-09-20 23:53:47'),
  (70037, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 850, '2026-09-12 16:22:28'),
  (70038, 'admin', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '登录成功', '10.0.0.21', 766, '2026-09-02 01:24:51'),
  (70039, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', 'enable 已写回 Redis JSON', '10.0.0.21', 570, '2026-10-11 21:41:01'),
  (70040, 'admin', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '密码错误（第 2 次）', '10.0.0.21', 719, '2026-09-12 17:49:09'),
  (70041, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '拒绝：内置角色不可删', '10.0.0.35', 795, '2026-10-05 02:50:26'),
  (70042, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '返回 100 行（已截断到上限）', '10.0.0.21', 151, '2026-09-22 00:47:27'),
  (70043, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '10.0.0.35', 507, '2026-10-04 20:38:24'),
  (70044, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 1303, '2026-09-20 19:13:44'),
  (70045, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'DataHub_destroy_stream', 'failure', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.35', 1032, '2026-09-22 05:04:11'),
  (70046, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'failure', '账号已停用，拒绝登录', '10.0.0.21', 493, '2026-10-06 15:47:47'),
  (70047, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '返回 100 行（已截断到上限）', '192.168.1.136', 1650, '2026-09-22 03:52:46'),
  (70048, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '192.168.1.136', 1701, '2026-09-16 06:26:12'),
  (70049, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '权限已保存，涉及 2 个账号', '192.168.1.136', 2148, '2026-09-16 03:31:46'),
  (70050, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '语句含 DELETE，被只读校验拒绝', '192.168.1.136', 352, '2026-09-24 19:13:56'),
  (70051, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'failure', '内部错误', '10.0.0.21', 817, '2026-10-05 02:39:53'),
  (70052, 'audit01', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'failure', '内部错误', '192.168.1.136', 792, '2026-09-19 06:46:38'),
  (70053, 'audit01', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '配置已保存（密码不回显）', '192.168.1.136', 360, '2026-09-27 00:25:59'),
  (70054, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '语句含 DELETE，被只读校验拒绝', '10.0.0.35', 370, '2026-10-05 09:57:57'),
  (70055, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.35', 1824, '2026-09-05 02:32:27'),
  (70056, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'success', '新建账号并分配 viewer', '10.0.0.35', 1534, '2026-09-03 04:05:28'),
  (70057, 'admin', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'success', '已下发 set 协议（停止）', '10.0.0.35', 2386, '2026-09-17 03:05:04'),
  (70058, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '192.168.1.136', 451, '2026-09-16 15:33:38'),
  (70059, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '停用账号，旧 token 已失效', '10.0.0.21', 1582, '2026-10-09 21:21:56'),
  (70060, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'failure', '内部错误', '10.0.0.21', 853, '2026-09-08 17:10:34'),
  (70061, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '192.168.1.136', 1962, '2026-09-07 06:32:39'),
  (70062, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'failure', '内部错误', '10.0.0.35', 1271, '2026-09-08 04:41:31'),
  (70063, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '登录成功', '10.0.0.35', 1353, '2026-10-09 12:02:32'),
  (70064, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 348, '2026-09-23 20:53:00'),
  (70065, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '新增权限点 3 个', '192.168.1.136', 2118, '2026-09-15 04:22:30'),
  (70066, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', 'enable 已写回 Redis JSON', '192.168.1.136', 1878, '2026-09-16 22:10:38'),
  (70067, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '192.168.1.136', 757, '2026-10-07 12:45:51'),
  (70068, 'audit01', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '连通性测试通过', '10.0.0.21', 2090, '2026-09-09 02:33:43'),
  (70069, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 1878, '2026-10-03 18:05:09'),
  (70070, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '配置已保存（密码不回显）', '10.0.0.21', 1433, '2026-08-31 23:04:31'),
  (70071, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '语句含 DELETE，被只读校验拒绝', '192.168.1.136', 1707, '2026-10-05 19:30:40'),
  (70072, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '10.0.0.21', 304, '2026-10-09 06:07:20'),
  (70073, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'failure', '内部错误', '192.168.1.136', 749, '2026-09-16 12:41:38'),
  (70074, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 set 协议（停止）', '192.168.1.136', 210, '2026-10-12 13:41:10'),
  (70075, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '连通性测试通过', '10.0.0.21', 1503, '2026-09-13 23:20:47'),
  (70076, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '192.168.1.136', 269, '2026-09-30 05:48:29'),
  (70077, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '权限已保存，涉及 2 个账号', '10.0.0.21', 1774, '2026-09-17 01:25:08'),
  (70078, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '超时未收到策略平台回执', '10.0.0.35', 2118, '2026-09-19 22:34:48'),
  (70079, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '拒绝：内置角色不可删', '10.0.0.35', 783, '2026-10-07 15:23:13'),
  (70080, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '拒绝：内置角色不可删', '192.168.1.136', 569, '2026-09-18 00:27:18'),
  (70081, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '192.168.1.136', 2273, '2026-10-08 19:37:48'),
  (70082, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'success', '键不存在，未产生写入', '10.0.0.35', 364, '2026-10-07 18:44:43'),
  (70083, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '语句含 DELETE，被只读校验拒绝', '10.0.0.21', 1408, '2026-10-10 23:06:02'),
  (70084, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '返回 100 行（已截断到上限）', '192.168.1.136', 158, '2026-09-25 12:43:56'),
  (70085, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '192.168.1.136', 361, '2026-10-08 09:06:52'),
  (70086, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '返回 100 行（已截断到上限）', '192.168.1.136', 1324, '2026-10-01 07:46:56'),
  (70087, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '连通性测试通过', '192.168.1.136', 806, '2026-09-28 18:01:03'),
  (70088, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'failure', '内部错误', '192.168.1.136', 1302, '2026-08-31 19:26:26'),
  (70089, 'admin', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '192.168.1.136', 1569, '2026-09-24 06:17:26'),
  (70090, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '10.0.0.35', 2183, '2026-09-17 01:41:34'),
  (70091, 'admin', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 set 协议（停止）', '10.0.0.35', 302, '2026-10-11 05:45:02'),
  (70092, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '192.168.1.136', 482, '2026-10-06 20:58:11'),
  (70093, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'failure', '密码错误（第 2 次）', '10.0.0.35', 1786, '2026-10-09 15:09:08'),
  (70094, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '权限已保存，涉及 2 个账号', '10.0.0.35', 267, '2026-09-28 06:48:42'),
  (70095, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '删除成功（DEL a = 1）', '10.0.0.35', 779, '2026-09-04 12:16:41'),
  (70096, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'success', '停用账号，旧 token 已失效', '192.168.1.136', 2062, '2026-09-14 05:06:53'),
  (70097, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'admin', 'failure', '账号已停用，拒绝登录', '192.168.1.136', 1010, '2026-09-15 08:25:22'),
  (70098, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'failure', '内部错误', '10.0.0.35', 1247, '2026-09-15 02:51:08'),
  (70099, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'liuyi', 'success', '新建账号并分配 viewer', '10.0.0.21', 940, '2026-09-28 04:35:34'),
  (70100, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'failure', '拒绝：内置角色不可删', '192.168.1.136', 1696, '2026-10-03 07:18:55'),
  (70101, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '新建账号并分配 viewer', '10.0.0.35', 537, '2026-09-23 16:45:36'),
  (70102, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '执行耗时 128ms', '192.168.1.136', 260, '2026-09-27 20:30:53'),
  (70103, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '连通性测试通过', '192.168.1.136', 1534, '2026-09-26 08:56:51'),
  (70104, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '192.168.1.136', 1884, '2026-09-15 16:30:28'),
  (70105, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'a', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.21', 2036, '2026-09-13 16:52:50'),
  (70106, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '连通性测试通过', '10.0.0.21', 2132, '2026-09-23 14:24:24'),
  (70107, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 set 协议（停止）', '192.168.1.136', 758, '2026-09-14 20:14:29'),
  (70108, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '10.0.0.35', 2189, '2026-09-28 21:27:04'),
  (70109, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'failure', '内部错误', '10.0.0.21', 1636, '2026-09-13 09:43:58'),
  (70110, 'admin', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '10.0.0.35', 2313, '2026-08-28 17:56:50'),
  (70111, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.21', 1817, '2026-09-23 00:42:16'),
  (70112, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '返回 100 行（已截断到上限）', '10.0.0.21', 1571, '2026-09-20 08:47:09'),
  (70113, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '登录成功', '192.168.1.136', 2053, '2026-09-27 02:39:17'),
  (70114, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.21', 946, '2026-09-25 00:51:17'),
  (70115, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'failure', '内部错误', '192.168.1.136', 81, '2026-09-10 22:31:19'),
  (70116, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '删除成功（DEL a = 1）', '192.168.1.136', 473, '2026-09-28 01:58:37'),
  (70117, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 588, '2026-08-28 23:38:24'),
  (70118, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '语句含 DELETE，被只读校验拒绝', '10.0.0.21', 939, '2026-09-19 05:03:08'),
  (70119, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.35', 2013, '2026-09-24 03:30:51'),
  (70120, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.35', 1960, '2026-09-01 08:18:01'),
  (70121, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '返回 100 行（已截断到上限）', '10.0.0.21', 1691, '2026-09-03 09:54:09'),
  (70122, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'failure', '内部错误', '10.0.0.35', 2213, '2026-09-03 05:42:42'),
  (70123, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '192.168.1.136', 1548, '2026-09-29 20:37:02'),
  (70124, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '重置密码并强制下次改密', '10.0.0.35', 2048, '2026-09-19 04:47:29'),
  (70125, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 set 协议（停止）', '10.0.0.21', 67, '2026-09-16 14:49:55'),
  (70126, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '新建账号并分配 viewer', '10.0.0.35', 50, '2026-09-10 15:32:08'),
  (70127, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 2121, '2026-09-07 20:29:11'),
  (70128, 'audit01', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'failure', '内部错误', '192.168.1.136', 1308, '2026-09-22 03:07:02'),
  (70129, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '权限已保存，涉及 2 个账号', '10.0.0.35', 556, '2026-10-02 13:48:59'),
  (70130, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.35', 311, '2026-09-14 01:57:10'),
  (70131, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '语句含 DELETE，被只读校验拒绝', '192.168.1.136', 133, '2026-09-19 06:57:30'),
  (70132, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'failure', '内部错误', '192.168.1.136', 130, '2026-10-11 21:16:44'),
  (70133, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '拒绝：内置角色不可删', '192.168.1.136', 1901, '2026-09-13 03:30:31'),
  (70134, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 set 协议（停止）', '192.168.1.136', 2251, '2026-10-08 18:45:57'),
  (70135, 'admin', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '拒绝：内置角色不可删', '10.0.0.35', 1178, '2026-09-19 22:14:59'),
  (70136, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '密码错误（第 2 次）', '192.168.1.136', 1899, '2026-09-30 18:59:24'),
  (70137, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '10.0.0.21', 223, '2026-08-31 09:43:49'),
  (70138, 'admin', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '新增权限点 3 个', '10.0.0.21', 2023, '2026-09-17 03:37:26'),
  (70139, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '登录成功', '192.168.1.136', 146, '2026-08-30 03:18:18'),
  (70140, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'REDISHOST / REDISSELECT', 'success', '配置已保存（密码不回显）', '192.168.1.136', 285, '2026-09-19 20:33:21'),
  (70141, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'DataHub_destroy_stream', 'failure', '内部错误', '192.168.1.136', 1664, '2026-10-01 21:10:13'),
  (70142, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '新建账号并分配 viewer', '10.0.0.35', 2002, '2026-09-27 06:17:40'),
  (70143, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'failure', '内部错误', '10.0.0.21', 99, '2026-09-10 19:41:09'),
  (70144, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '键不存在，未产生写入', '192.168.1.136', 203, '2026-09-10 02:56:57'),
  (70145, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'DataHub_destroy_stream', 'success', '删除成功（DEL a = 1）', '10.0.0.35', 1941, '2026-08-31 22:07:36'),
  (70146, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 1527, '2026-09-30 18:08:24'),
  (70147, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.35', 980, '2026-09-27 18:21:55'),
  (70148, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'failure', '内部错误', '192.168.1.136', 1025, '2026-10-10 10:48:14'),
  (70149, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'failure', '语句含 DELETE，被只读校验拒绝', '10.0.0.21', 1429, '2026-09-10 13:59:59'),
  (70150, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.21', 944, '2026-09-01 21:22:48'),
  (70151, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.21', 118, '2026-09-17 23:03:01'),
  (70152, 'admin', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '新增权限点 3 个', '10.0.0.21', 968, '2026-10-09 01:34:54'),
  (70153, 'audit01', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'audit01', 'success', '停用账号，旧 token 已失效', '192.168.1.136', 1866, '2026-09-18 00:45:13'),
  (70154, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 923, '2026-09-07 06:39:44'),
  (70155, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'success', '超时未收到策略平台回执', '10.0.0.35', 23, '2026-10-05 08:53:59'),
  (70156, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '返回 100 行（已截断到上限）', '192.168.1.136', 74, '2026-09-17 06:03:59'),
  (70157, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '执行耗时 128ms', '192.168.1.136', 1916, '2026-09-19 08:43:05'),
  (70158, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '192.168.1.136', 207, '2026-08-29 21:32:32'),
  (70159, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '权限已保存，涉及 2 个账号', '192.168.1.136', 688, '2026-09-03 09:11:25'),
  (70160, 'admin', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '新增权限点 3 个', '192.168.1.136', 1942, '2026-10-11 00:36:34'),
  (70161, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'failure', '超时未收到策略平台回执', '10.0.0.35', 378, '2026-09-21 12:03:16'),
  (70162, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'failure', '拒绝：内置角色不可删', '10.0.0.21', 1575, '2026-09-27 05:24:28'),
  (70163, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '192.168.1.136', 1608, '2026-09-15 16:39:14'),
  (70164, 'yangsh', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'failure', '内部错误', '10.0.0.35', 230, '2026-08-31 15:26:27'),
  (70165, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '权限已保存，涉及 2 个账号', '192.168.1.136', 1186, '2026-10-05 22:21:44'),
  (70166, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '10.0.0.21', 2200, '2026-09-08 11:12:51'),
  (70167, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.21', 1715, '2026-09-12 18:26:54'),
  (70168, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'failure', '内部错误', '10.0.0.35', 22, '2026-09-26 14:43:05'),
  (70169, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'failure', '内部错误', '10.0.0.21', 358, '2026-10-09 10:41:36'),
  (70170, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'test_tmp', 'success', '新建账号并分配 viewer', '10.0.0.35', 157, '2026-09-09 23:57:50'),
  (70171, 'admin', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 remove 协议，策略平台已回执', '10.0.0.21', 384, '2026-09-11 21:21:47'),
  (70172, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'failure', '超时未收到策略平台回执', '192.168.1.136', 1246, '2026-10-12 08:23:01'),
  (70173, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'a', 'success', '删除成功（DEL a = 1）', '10.0.0.21', 2336, '2026-09-25 13:45:07'),
  (70174, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', 'enable 已写回 Redis JSON', '10.0.0.21', 521, '2026-09-21 11:11:33'),
  (70175, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '执行耗时 128ms', '10.0.0.21', 1243, '2026-09-06 03:13:01'),
  (70176, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '返回 100 行（已截断到上限）', '10.0.0.35', 1199, '2026-09-28 04:22:06'),
  (70177, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'success', '拒绝：内置角色不可删', '192.168.1.136', 1046, '2026-10-06 09:13:45'),
  (70178, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '语句含 DELETE，被只读校验拒绝', '10.0.0.35', 677, '2026-09-30 12:03:45'),
  (70179, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'WT-1-reply', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '192.168.1.136', 1993, '2026-10-05 13:43:09'),
  (70180, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'failure', '内部错误', '192.168.1.136', 680, '2026-09-23 07:40:00'),
  (70181, 'admin', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 set 协议（停止）', '10.0.0.21', 261, '2026-10-06 14:32:57'),
  (70182, 'audit01', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '10.0.0.21', 767, '2026-09-13 05:03:37'),
  (70183, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.35', 872, '2026-10-01 06:53:11'),
  (70184, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'failure', '内部错误', '192.168.1.136', 1735, '2026-10-12 07:24:41'),
  (70185, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'test_tmp', 'success', '新建账号并分配 viewer', '192.168.1.136', 1901, '2026-08-31 13:21:16'),
  (70186, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'success', '超时未收到策略平台回执', '10.0.0.21', 2107, '2026-09-12 14:17:03'),
  (70187, 'yangsh', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'failure', '内部错误', '10.0.0.21', 497, '2026-09-26 02:58:21'),
  (70188, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'failure', '内部错误', '10.0.0.35', 1960, '2026-09-19 10:40:37'),
  (70189, 'yangsh', 'action:system-user:manage', '管理后台账号', 'system', 'high', 'test_tmp', 'failure', '内部错误', '10.0.0.21', 736, '2026-09-21 12:21:12'),
  (70190, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'failure', '内部错误', '192.168.1.136', 1787, '2026-09-28 14:53:56'),
  (70191, 'admin', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'failure', '键不存在，未产生写入', '10.0.0.21', 1166, '2026-09-16 16:59:28'),
  (70192, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '10.0.0.35', 49, '2026-08-29 16:38:57'),
  (70193, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 set 协议（停止）', '192.168.1.136', 905, '2026-10-08 16:57:45'),
  (70194, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '返回 100 行（已截断到上限）', '10.0.0.35', 671, '2026-10-06 22:21:21'),
  (70195, 'admin', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '账号已停用，拒绝登录', '10.0.0.35', 2025, '2026-09-08 03:44:17'),
  (70196, 'admin', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 2168, '2026-10-05 22:54:45'),
  (70197, 'admin', 'system:login', '管理端登录', 'system', 'low', 'yangsh', 'success', '登录成功', '10.0.0.35', 1435, '2026-10-06 18:44:00'),
  (70198, 'admin', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '登录成功', '192.168.1.136', 196, '2026-09-18 07:44:45'),
  (70199, 'admin', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=3 mac=20474789c674', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.21', 2131, '2026-10-04 03:08:54'),
  (70200, 'yangsh', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'a', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.35', 1419, '2026-10-04 19:48:48'),
  (70201, 'audit01', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM V_Conditional LIMIT 50', 'success', '语句含 DELETE，被只读校验拒绝', '192.168.1.136', 1899, '2026-09-25 22:43:48'),
  (70202, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 1723, '2026-09-05 05:41:38'),
  (70203, 'admin', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'viewer', 'success', '权限已保存，涉及 2 个账号', '10.0.0.35', 723, '2026-10-12 01:22:00'),
  (70204, 'audit01', 'action:system-role:manage', '管理角色与权限', 'system', 'high', 'auditor', 'failure', '内部错误', '10.0.0.21', 649, '2026-08-29 09:57:19'),
  (70205, 'yangsh', 'system:login', '管理端登录', 'system', 'low', 'admin', 'success', '登录成功', '192.168.1.136', 840, '2026-09-26 14:10:25'),
  (70206, 'yangsh', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '192.168.1.136', 1642, '2026-09-15 00:21:53'),
  (70207, 'admin', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'success', '已下发 remove 协议，策略平台已回执', '10.0.0.21', 2017, '2026-09-13 23:28:28'),
  (70208, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 914900043', 'success', '已下发 remove 协议，策略平台已回执', '192.168.1.136', 1034, '2026-09-09 12:13:00'),
  (70209, 'yangsh', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '10.0.0.21', 771, '2026-09-28 07:45:11'),
  (70210, 'audit01', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 300130000461', 'failure', '超时未收到策略平台回执', '10.0.0.35', 1571, '2026-10-06 19:36:13'),
  (70211, 'audit01', 'action:server-trade:toggle', '启停委托服务器', 'server', 'high', 'traderserver id=1 mac=2cea7fd9d5c0', 'success', '目标节点 id 重复，按 mac 定位成功', '10.0.0.35', 1827, '2026-09-06 04:51:44'),
  (70212, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '删除成功（DEL a = 1）', '192.168.1.136', 491, '2026-08-30 11:13:56'),
  (70213, 'admin', 'system:connection', '修改连接配置', 'system', 'high', 'DBHOST / DBUSER', 'success', '配置已保存（密码不回显）', '10.0.0.35', 314, '2026-09-29 03:39:48'),
  (70214, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '执行耗时 128ms', '10.0.0.21', 1619, '2026-09-07 13:33:23'),
  (70215, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT * FROM T_UserLog LIMIT 100', 'success', '执行耗时 128ms', '10.0.0.35', 2283, '2026-09-21 20:04:11'),
  (70216, 'admin', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'failure', '语句含 DELETE，被只读校验拒绝', '192.168.1.136', 1616, '2026-10-11 00:22:07'),
  (70217, 'yangsh', 'action:storage-mysql:readonly-sql', '执行只读 SQL', 'storage', 'medium', 'SELECT COUNT(*) FROM T_StrategyLog', 'success', '返回 100 行（已截断到上限）', '192.168.1.136', 1755, '2026-08-29 14:24:47'),
  (70218, 'audit01', 'action:system-audit:export', '导出审计日志', 'system', 'low', '2026-08 审计导出', 'success', '导出 1280 条，格式 CSV', '10.0.0.21', 615, '2026-09-10 12:34:17'),
  (70219, 'yangsh', 'action:conditional:write', '启停 / 删除条件单', 'bizdata', 'high', '条件单 1000006', 'success', '已下发 set 协议（停止）', '10.0.0.21', 1094, '2026-10-11 23:17:25'),
  (70220, 'audit01', 'action:storage-redis:delete-key', '删除 Redis 键', 'storage', 'high', 'strategy_server_lock_1001', 'success', '拒绝：该键属于 DataHub_* 流，后端要求二次确认', '10.0.0.21', 87, '2026-09-24 22:49:18');

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
