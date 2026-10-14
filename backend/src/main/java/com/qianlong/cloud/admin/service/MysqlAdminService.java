package com.qianlong.cloud.admin.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlColumnView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlIndexView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlTableSchemaView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.MysqlTableView;
import com.qianlong.cloud.admin.dto.MysqlAdminDtos.ReadonlyQueryResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据库管理：表清单、表结构、只读查询。
 *
 * <h2>⚠️ 本模块是全项目最高危的地方</h2>
 * 现场的应用数据库账号是
 * {@code GRANT ALL PRIVILEGES ON qianlong_cloud.*} —— 也就是
 * <b>一条没被拦住的 SQL 就能删表</b>。而实测发现数据库自己的只读事务
 * **挡不住 DDL**（{@code CREATE TABLE} 会隐式提交从而逃逸，见 {@link ReadOnlySqlGuard} 的说明）。
 *
 * <p>所以这里的防护是**三层**，任何一层单独都不够：
 * <ol>
 *   <li><b>应用层白名单</b>（{@link ReadOnlySqlGuard}）—— 只放行明确只读的语句形状，
 *       拒绝多语句与危险关键字。**这是主要防线**；</li>
 *   <li><b>数据库只读事务</b>（{@code START TRANSACTION READ ONLY}）——
 *       实测能挡住 INSERT/UPDATE/DELETE；</li>
 *   <li><b>结果集上限</b> —— 强制 {@code maxRows}，防止一条
 *       {@code SELECT * FROM T_UserLog} 把 19 万行全拉进内存。</li>
 * </ol>
 *
 * <p><b>最彻底的做法</b>是给只读页面单独建一个只有 {@code SELECT} 权限的账号，
 * 但需要现场 DBA 配合建号授权。本次**没有擅自改数据库账号**
 * （已在 README 记为建议项）。在那之前，第 1 层白名单不可放松。
 *
 * <h2>⚠️ 表清单的两个行数字段</h2>
 * {@code information_schema.TABLES.TABLE_ROWS} **不准** ——
 * 现场实测 {@code T_StrategyLog} 报 16566、实际 17430。
 * 所以另给 {@code exactRows}（真实 {@code COUNT(*)}），
 * 且**只对小表算**（大表 COUNT 代价高，现场 {@code T_UserLog} 19 万行要几百毫秒）。
 * 两个字段并存是刻意的：页面要能看出「近似值不可信」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlAdminService {

    /** 只读查询的行数硬上限（前端传更大也没用） */
    private static final int MAX_QUERY_ROWS = 200;

    /**
     * 自动计算精确行数的阈值。
     *
     * <p>超过这个近似值的表不再 {@code COUNT(*)} —— 那会在大表上产生明显代价，
     * 而页面并不需要精确到行。字段留 null，页面显示「—」并说明原因。
     */
    private static final long EXACT_ROWS_THRESHOLD = 100_000L;

    /** 单次查询的超时（秒）：防止有人用 SLEEP / 大笛卡尔积把连接占死 */
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    private final DataSource dataSource;

    /* ============================== 表清单 ============================== */

    /**
     * 表/视图清单（含注释与行数）。
     *
     * @param database 库名。**白名单校验**为当前连接的库 —— 见 resolveDatabase
     */
    public List<MysqlTableView> listTables(String database) {
        String db = resolveDatabase(database);

        String sql = """
                SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_ROWS, DATA_LENGTH,
                       INDEX_LENGTH, TABLE_COMMENT
                  FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = ?
                 ORDER BY TABLE_TYPE, TABLE_NAME
                """;
        List<MysqlTableView> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    Long approx = nullableLong(rs, "TABLE_ROWS");
                    out.add(new MysqlTableView(
                            name,
                            type,
                            rs.getString("ENGINE"),
                            approx,
                            // 只对小表算精确行数：大表 COUNT(*) 代价高
                            (approx != null && approx <= EXACT_ROWS_THRESHOLD && "BASE TABLE".equals(type))
                                    ? exactCount(conn, name) : null,
                            nullableLong(rs, "DATA_LENGTH"),
                            nullableLong(rs, "INDEX_LENGTH"),
                            blankToNull(rs.getString("TABLE_COMMENT"))));
                }
            }
        } catch (SQLException ex) {
            log.error("查表清单失败 database={}", db, ex);
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "读取表清单失败：" + ex.getMessage());
        }
        return out;
    }

    /* ============================== 表结构 ============================== */

    /** 单表结构：字段、索引、DDL */
    public MysqlTableSchemaView getTableSchema(String database, String table) {
        String db = resolveDatabase(database);
        String tbl = requireTableName(table);
        // 表名必须真实存在：不这样的话，show create table 会把库名当表名之类的怪输入
        if (!tableExists(db, tbl)) {
            throw BizException.notFound("表或视图不存在：" + db + "." + tbl);
        }

        try (Connection conn = dataSource.getConnection()) {
            return new MysqlTableSchemaView(tbl, columnsOf(conn, db, tbl),
                    indexesOf(conn, db, tbl), ddlOf(conn, tbl));
        } catch (SQLException ex) {
            log.error("查表结构失败 {}.{}", db, tbl, ex);
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "读取表结构失败：" + ex.getMessage());
        }
    }

    private List<MysqlColumnView> columnsOf(Connection conn, String db, String table) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY,
                       COLUMN_DEFAULT, COLUMN_COMMENT, EXTRA
                  FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                 ORDER BY ORDINAL_POSITION
                """;
        List<MysqlColumnView> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MysqlColumnView(
                            rs.getString("COLUMN_NAME"),
                            rs.getString("COLUMN_TYPE"),
                            "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                            blankToNull(rs.getString("COLUMN_KEY")),
                            rs.getString("COLUMN_DEFAULT"),
                            blankToNull(rs.getString("COLUMN_COMMENT")),
                            blankToNull(rs.getString("EXTRA"))));
                }
            }
        }
        return out;
    }

    private List<MysqlIndexView> indexesOf(Connection conn, String db, String table) throws SQLException {
        String sql = """
                SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
                  FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                 ORDER BY INDEX_NAME, SEQ_IN_INDEX
                """;
        // 一个索引有多列，需要按名字聚合（SEQ_IN_INDEX 保序）
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    cols.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
                    unique.put(name, rs.getInt("NON_UNIQUE") == 0);
                }
            }
        }
        List<MysqlIndexView> out = new ArrayList<>();
        cols.forEach((name, columns) -> out.add(new MysqlIndexView(name, unique.get(name), columns)));
        return out;
    }

    /** {@code SHOW CREATE TABLE / VIEW} 的原文（页面要原样展示，不做加工） */
    private String ddlOf(Connection conn, String table) throws SQLException {
        // ⚠️ 表名来自 tableExists 校验过的真实表名；且用反引号包裹，
        //    这里是**标识符拼接**（不能参数化），所以上面的存在性校验是必须的
        //
        // ⚠️ 视图与表的返回列数不同：SHOW CREATE TABLE 返回 2 列（表名, DDL），
        //    而 SHOW CREATE VIEW 返回 **4 列**（视图名, DDL, character_set_client,
        //    collation_connection）—— 取「最后一列」在视图上会拿到字符集，
        //    页面显示的 DDL 就变成 "utf8mb4" 了（实测踩到）。
        //    DDL 永远是**第 2 列**，所以固定取 2。
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + table.replace("`", "``") + "`")) {
            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (SQLException ex) {
            // 视图要用 SHOW CREATE VIEW（SHOW CREATE TABLE 对视图也能用，
            // 但部分版本/权限下会失败），失败时退回 VIEW
            log.debug("SHOW CREATE TABLE 失败，改用 SHOW CREATE VIEW：{}", ex.getMessage());
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW CREATE VIEW `" + table.replace("`", "``") + "`")) {
                if (rs.next()) {
                    return rs.getString(2);
                }
            }
        }
        return null;
    }

    /* ============================== 只读查询 ============================== */

    /**
     * 执行一条**只读** SQL。
     *
     * <p>三层防护见类注释。注意第 2 层（只读事务）对 DDL **无效**
     * （会隐式提交逃逸），所以第 1 层白名单才是关键，
     * 这里的只读事务只是「万一白名单漏了 DML」的兜底。
     */
    public ReadonlyQueryResult runReadonlyQuery(String database, String rawSql, Integer limit) {
        String db = resolveDatabase(database);
        ReadOnlySqlGuard.Validation valid = ReadOnlySqlGuard.validate(rawSql);

        int maxRows = MAX_QUERY_ROWS;
        if (limit != null && limit > 0) {
            // 前端可以要求更少，但**不能**要求更多
            maxRows = Math.min(limit, MAX_QUERY_ROWS);
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;

        try (Connection conn = dataSource.getConnection()) {
            // 第 2 层：只读事务（能挡 DML；DDL 会逃逸，所以不能只靠它）
            try (Statement st = conn.createStatement()) {
                st.execute("START TRANSACTION READ ONLY");
            }
            conn.setReadOnly(true);

            try (Statement st = conn.createStatement()) {
                // 第 3 层：超时 + 结果集上限
                st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                st.setMaxRows(maxRows + 1);   // 多取一行用于判断「是否被截断」
                try (ResultSet rs = st.executeQuery(valid.sql())) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            // 用列标签（别名优先），页面表头直接用它
                            row.put(meta.getColumnLabel(i), normalizeValue(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                }
            } finally {
                // 无论成败都回滚，绝不留未提交事务（只读也不留）
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 连接要关了，回滚失败无所谓
                }
            }
        } catch (SQLException ex) {
            // SQL 语法/语义错误如实回给使用者 —— 这是他写错了，不是系统故障
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("只读查询执行失败：{} -> {}", abbreviate(valid.sql()), msg);
            throw BizException.badRequest("SQL 执行失败：" + msg);
        }

        long cost = System.currentTimeMillis() - start;
        String note = truncated
                ? "结果超过上限 " + maxRows + " 行，已截断（请加 WHERE / LIMIT 缩小范围）"
                : null;
        if (truncated) {
            log.info("只读查询结果被截断（上限 {} 行），sql={}", maxRows, abbreviate(valid.sql()));
        }

        return new ReadonlyQueryResult(rows, rows.size(), 1, maxRows, truncated, maxRows, cost, note);
    }

    /* ============================== 分页取表数据 ============================== */

    /**
     * 分页读一张表的数据（**只读**，页面「数据」tab 用）。
     *
     * <p><b>为什么不用 {@code SELECT *} 直接拼 OFFSET</b>：
     * 表名与列名是标识符，不能参数化，所以这里**只用真实存在的表名**
     * （先过 {@code tableExists}），并把 pageSize 硬限制在
     * {@link #MAX_QUERY_ROWS}。不做任意 SQL —— 任意 SQL 走
     * {@link #runReadonlyQuery} 那条有白名单校验的路径。
     */
    public ReadonlyQueryResult queryTable(String database, String table, Integer page, Integer pageSize) {
        String db = resolveDatabase(database);
        String tbl = requireTableName(table);
        if (!tableExists(db, tbl)) {
            throw BizException.notFound("表或视图不存在：" + db + "." + tbl);
        }

        int size = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, MAX_QUERY_ROWS);
        int p = (page == null || page < 1) ? 1 : page;
        long offset = (long) (p - 1) * size;

        // 用反引号包裹真实表名（已校验存在）；LIMIT/OFFSET 是数字，直接拼安全
        String sql = "SELECT * FROM `" + tbl.replace("`", "``") + "` LIMIT " + size + " OFFSET " + offset;
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                st.setMaxRows(size);
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(meta.getColumnLabel(i), normalizeValue(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (SQLException ex) {
            log.error("分页读表失败 {}.{}", db, tbl, ex);
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "读取表数据失败：" + ex.getMessage());
        }

        // total 用 COUNT(*)：分页控件需要它；表太大时它是 null/OFFSET 也照常工作
        Long total = exactCount2(db, tbl);
        return new ReadonlyQueryResult(rows, total == null ? rows.size() : total,
                p, size, false, MAX_QUERY_ROWS,
                System.currentTimeMillis() - start, null);
    }

    /** 计数（分页用）。失败返回 null，页面退化成「本页条数」而不是假装 0 */
    private Long exactCount2(String db, String table) {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM `" + table.replace("`", "``") + "`")) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException ex) {
            log.warn("COUNT(*) 失败 {}.{} -> {}", db, table, ex.getMessage());
            return null;
        }
    }

    /* ============================== 工具 ============================== */

    /**
     * 库名白名单 —— **只允许当前连接的库**。
     *
     * <p>为什么要校验：库名会进 SQL（{@code information_schema} 的查询是参数化的，
     * 但 {@code SHOW CREATE TABLE} 那里用的是标识符拼接）。
     * 而且现场账号虽然读不到 {@code mysql} 库（实测 1142 无权限），
     * 但 {@code information_schema} 与 {@code performance_schema} 是可见的 ——
     * 放开库名等于把这两个库也暴露出去，没有必要。
     */
    private String resolveDatabase(String database) {
        String current;
        try (Connection conn = dataSource.getConnection()) {
            current = conn.getCatalog();
        } catch (SQLException ex) {
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "无法确定当前数据库：" + ex.getMessage());
        }
        String requested = StringUtils.hasText(database) ? database.trim() : current;
        if (!requested.equalsIgnoreCase(current)) {
            // 如实说明：只允许当前库。不静默改成当前库（那会让人以为查的是他指定的库）
            throw BizException.badRequest(
                    "只允许访问当前数据库「" + current + "」，请求的是「" + requested + "」");
        }
        return current;
    }

    /** 表名非空校验（真正的存在性校验在 tableExists） */
    private static String requireTableName(String table) {
        if (!StringUtils.hasText(table)) {
            throw BizException.badRequest("必须提供表名");
        }
        String t = table.trim();
        // 表名不允许含反引号或点（防标识符拼接逃逸）；真实表名也不会含这些
        if (t.contains("`") || t.contains(".") || t.contains("\\") || t.contains("/")
                || t.contains("'") || t.contains("\"")) {
            throw BizException.badRequest("表名含非法字符：" + t);
        }
        return t;
    }

    private boolean tableExists(String db, String table) {
        String sql = """
                SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, db);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException ex) {
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "校验表名失败：" + ex.getMessage());
        }
    }

    /** 真实行数 COUNT(*)。失败返回 null（页面显示「—」而不是 0，避免把失败当空表） */
    private static Long exactCount(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM `" + table.replace("`", "``") + "`")) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException ex) {
            log.warn("COUNT(*) 失败 table={} -> {}", table, ex.getMessage());
            return null;
        }
    }

    /**
     * JDBC 值的 JSON 友好化。
     *
     * <p>关键是 {@code byte[]}（现场 {@code T_User.RegProtocol} 是 {@code binary(1)}）：
     * 不处理的话 Jackson 会把它序列化成 Base64，页面显示的是一串乱码，
     * 而实际内容是 {@code '1'}。这里按可打印 ASCII 显示，否则退化成十六进制。
     */
    private static Object normalizeValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            boolean printable = true;
            for (byte b : bytes) {
                if (b >= 32 && b < 127) {
                    sb.append((char) b);
                } else {
                    printable = false;
                    break;
                }
            }
            if (printable) {
                return sb.toString();
            }
            StringBuilder hex = new StringBuilder("0x");
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.toString();
        }
        // 时间类型统一按字符串给出，避免不同驱动序列化格式不一致
        if (v instanceof java.sql.Timestamp ts) {
            return com.qianlong.cloud.admin.common.DateTimes.format(ts.toLocalDateTime());
        }
        if (v instanceof java.sql.Date d) {
            return com.qianlong.cloud.admin.common.DateTimes.formatLocalDate(d.toLocalDate());
        }
        if (v instanceof java.sql.Time t) {
            return t.toString();
        }
        if (v instanceof java.math.BigDecimal || v instanceof java.math.BigInteger) {
            // 保持数值类型（前端会自行格式化），不转字符串以免丢精度
            return v;
        }
        return v;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "...";
    }
}
