package com.qianlong.cloud.admin.dto;

import java.util.List;
import java.util.Map;

/**
 * 数据库管理模块的出入参 DTO —— 字段名与前端 {@code src/api/domain.ts} 的
 * {@code MysqlTable} / {@code MysqlColumn} / {@code MysqlTableSchema} 逐一对齐。
 */
public final class MysqlAdminDtos {

    private MysqlAdminDtos() {
    }

    /**
     * 表/视图摘要，对应前端 {@code MysqlTable}。
     *
     * <p>⚠️ {@code approxRows} 来自 {@code information_schema.TABLES.TABLE_ROWS}，
     * <b>不准</b>（现场实测 {@code T_StrategyLog} 报 16566、实际 17430）。
     * 所以另有 {@code exactRows}（真实 {@code COUNT(*)}）。
     * 两个字段并存是刻意的：页面要能看出「近似值不可信」这件事。
     */
    public record MysqlTableView(
            String name,
            /** BASE TABLE / VIEW */
            String type,
            String engine,
            /** information_schema 报告的近似行数（不准） */
            Long approxRows,
            /** 真实行数 COUNT(*)；仅对小表计算，大表为 null */
            Long exactRows,
            Long dataLength,
            Long indexLength,
            String comment) {
    }

    /** 字段定义，对应前端 {@code MysqlColumn} */
    public record MysqlColumnView(
            String name,
            /** 完整类型，如 varchar(45) / binary(1) / datetime */
            String columnType,
            boolean nullable,
            /** PRI / UNI / MUL / 空 */
            String key,
            String defaultValue,
            String comment,
            /** 是否自增（extra 里有 auto_increment） */
            String extra) {
    }

    /** 索引 */
    public record MysqlIndexView(String name, boolean unique, List<String> columns) {
    }

    /** 表结构，对应前端 {@code MysqlTableSchema} */
    public record MysqlTableSchemaView(
            String name,
            List<MysqlColumnView> columns,
            List<MysqlIndexView> indexes,
            /** SHOW CREATE TABLE 的原文 */
            String ddl) {
    }

    /**
     * 只读查询结果。
     *
     * <p>前端只用到 {@code list}（表格数据），其余字段供排查与提示。
     */
    public record ReadonlyQueryResult(
            List<Map<String, Object>> list,
            long total,
            long page,
            long pageSize,
            /** 是否因结果上限被截断（**如实告知**，不静默截断） */
            boolean truncated,
            /** 服务端强制的行数上限 */
            int maxRows,
            /** 实际执行耗时（毫秒） */
            long costMs,
            /** 补充说明（如「已按上限截断」），前端会以警告条展示 */
            String note) {
    }
}
