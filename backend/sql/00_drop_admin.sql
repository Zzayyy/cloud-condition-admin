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
