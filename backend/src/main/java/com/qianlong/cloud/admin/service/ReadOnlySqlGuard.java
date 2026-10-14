package com.qianlong.cloud.admin.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qianlong.cloud.admin.common.BizException;

import lombok.extern.slf4j.Slf4j;

/**
 * 只读 SQL 的守卫 —— 本项目的**最高危代码**，独立成一个类便于单测与审阅。
 *
 * <h2>⚠️ 为什么不能只靠数据库的只读模式</h2>
 * 现场实测（2026-10-14）：
 * <pre>
 *   START TRANSACTION READ ONLY;
 *   INSERT / UPDATE / DELETE   -> errno 1792 被挡 ✅
 *   CREATE TABLE               -> **成功了** ❌
 *   DROP TABLE                 -> **成功了** ❌
 * </pre>
 * 原因：DDL 会**隐式提交**，从而结束只读事务，之后就在普通会话里执行了。
 * 而本项目的数据库账号是
 * {@code GRANT ALL PRIVILEGES ON qianlong_cloud.* } ——
 * 也就是说，一旦只读校验被绕过，**是真的能删掉生产表的**。
 *
 * <p>所以这里采取的是**应用层白名单**：只放行「明确只读」的语句形状，
 * 其余一律拒绝。数据库的只读事务仅作为第二层（见 {@code MysqlAdminService}）。
 *
 * <h2>校验步骤（顺序不能变）</h2>
 * <ol>
 *   <li><b>先剥字符串字面量与注释</b>：否则 {@code SELECT 'DROP TABLE x'} 会误报，
 *       而把关键字藏进注释的写法又会漏报 —— 两者都不可接受
 *       （此处不写出那个字面例子：它含有会提前截断本注释的字符序列）；</li>
 *   <li><b>拒绝多语句</b>：分号是「一条查询」与「一串命令」的分界，
 *       {@code SELECT 1; DROP TABLE T_User} 必须被拦；</li>
 *   <li><b>首关键字白名单</b>：只允许 SELECT / WITH / SHOW / DESC / DESCRIBE / EXPLAIN；</li>
 *   <li><b>危险关键字黑名单</b>（整词匹配）：DDL、DML、权限、文件、锁、会话控制。</li>
 * </ol>
 *
 * <p><b>已知边界</b>：这是黑名单 + 白名单的组合，不是 SQL 语法分析器。
 * 真正的强隔离应当是**给只读页面单独建一个只有 SELECT 权限的数据库账号** ——
 * 那是最彻底的做法，但需要现场 DBA 配合建号授权，
 * 本次没有擅自改数据库账号（已在 README 记为建议项）。
 * 在此之前，本类的白名单是必要的防线，且**不可**因为「数据库有只读事务」而放松。
 */
@Slf4j
public final class ReadOnlySqlGuard {

    private ReadOnlySqlGuard() {
    }

    /**
     * 只允许这些**首关键字**。
     *
     * <p>全部是读语句：
     * <ul>
     *   <li>{@code SELECT} / {@code WITH}（CTE 也以 SELECT 收尾）</li>
     *   <li>{@code SHOW} / {@code DESC} / {@code DESCRIBE} —— DBA 查表结构常用</li>
     *   <li>{@code EXPLAIN} —— 看执行计划，只读</li>
     * </ul>
     * 刻意**不**放行 {@code SET} / {@code CALL} / {@code DO} / {@code HANDLER} /
     * {@code PREPARE} 这些「看着无害但能改会话状态或执行动态 SQL」的语句。
     */
    private static final List<String> ALLOWED_FIRST_KEYWORDS =
            List.of("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");

    /**
     * 危险关键字黑名单（整词匹配）。
     *
     * <p>注意每一个都不是「顺手加的」：
     * <ul>
     *   <li>{@code INTO OUTFILE} / {@code DUMPFILE} —— 能往服务器磁盘写文件；</li>
     *   <li>{@code FOR UPDATE} / {@code LOCK IN SHARE MODE} —— 加锁会阻塞生产写入；</li>
     *   <li>{@code LOAD_FILE()} —— 能读服务器上的任意文件；</li>
     *   <li>{@code BENCHMARK} / {@code SLEEP} —— 故意拖慢实例（DoS）；</li>
     *   <li>{@code SET} / {@code CALL} / {@code EXECUTE} —— 能改会话状态或跑动态 SQL。</li>
     * </ul>
     */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            // DML / DDL
            "INSERT", "UPDATE", "DELETE", "REPLACE", "MERGE",
            "DROP", "CREATE", "ALTER", "TRUNCATE", "RENAME",
            // 权限
            "GRANT", "REVOKE",
            // 文件与导出
            "OUTFILE", "DUMPFILE", "LOAD_FILE", "LOAD",
            // 锁（会阻塞生产）
            "LOCK", "UNLOCK", "FOR UPDATE",
            // 会话 / 事务 / 管理
            "SET", "CALL", "DO", "HANDLER", "PREPARE", "EXECUTE", "DEALLOCATE",
            "COMMIT", "ROLLBACK", "SAVEPOINT", "BEGIN", "START", "XA",
            "FLUSH", "RESET", "PURGE", "KILL", "SHUTDOWN", "CHANGE", "MODIFY",
            "ANALYZE", "OPTIMIZE", "REPAIR", "CHECK", "INSTALL", "UNINSTALL",
            "BINLOG", "RELOAD", "SLAVE", "MASTER", "CHANGE MASTER",
            // 拖慢实例
            "BENCHMARK", "SLEEP");

    /** 多语句检测：剥掉字面量/注释后，语句内部不允许出现分号 */
    private static final Pattern SEMICOLON = Pattern.compile(";");

    /**
     * 允许的「SHOW CREATE ...」形态。
     *
     * <p><b>为什么需要这个例外：</b>{@code SHOW CREATE TABLE t} 是**纯只读**语句
     * （DBA 最常用的看表结构方式），但它含有 {@code CREATE} ——
     * 而 {@code CREATE} 在黑名单里。不加例外就会把正常查询拦掉，
     * 而「因为怕麻烦就把 CREATE 从黑名单拿掉」是绝对不能做的。
     *
     * <p>所以做法是：**精确放行这一种形状**，其余照旧拦。
     * 匹配后会把这段前缀从待扫描文本里去掉，后面的部分仍要过黑名单
     * （例如 {@code SHOW CREATE TABLE t; DROP TABLE x} 仍会被分号检查拦下）。
     */
    private static final Pattern SHOW_CREATE_PREFIX = Pattern.compile(
            "^\\s*SHOW\\s+CREATE\\s+(TABLE|VIEW|DATABASE|SCHEMA|INDEX|FUNCTION|PROCEDURE|TRIGGER|EVENT)\\b",
            Pattern.CASE_INSENSITIVE);

    public record Validation(String sql) {
    }

    /**
     * 校验并规范化一条只读 SQL。
     *
     * @return 规范化后的 SQL（去掉末尾分号）
     * @throws BizException 任何不合规的情况，消息面向使用者、指明原因
     */
    public static Validation validate(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw BizException.badRequest("SQL 不能为空");
        }

        // 长度上限：防止有人贴一整本 SQL 进来把校验与执行拖慢
        if (rawSql.length() > 10_000) {
            throw BizException.badRequest("SQL 过长（上限 10000 字符），请拆分后再执行");
        }

        String stripped = stripLiteralsAndComments(rawSql);

        // 1) 多语句
        String trimmed = stripped.trim();
        // 允许末尾一个分号（用户习惯性输入），但**内部**不允许
        String withoutTrailing = trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
        if (SEMICOLON.matcher(withoutTrailing).find()) {
            throw BizException.badRequest(
                    "不允许一次执行多条语句（检测到分号）。本页只允许单条只读查询。");
        }

        /*
         * 1b) **拒绝可执行注释**（MySQL 特有语法）。
         *
         * 这一类注释里的内容会被 MySQL **真的执行**，而它长得像注释 ——
         * 所以「剥掉注释再校验、执行原文」这种做法会被它绕过：
         * 校验时看到的是 SELECT 1，执行时却把注释里的语句跑了一遍。
         *
         * 处理方式是**直接拒绝**而不是剥掉后执行：
         * 正常的只读查询没有任何理由使用可执行注释，
         * 留着它只会让「校验的文本」与「执行的文本」不一致 ——
         * 而那种不一致正是漏洞的温床。
         */
        if (rawSql.contains("/*!")) {
            throw BizException.badRequest(
                    "SQL 含可执行注释（/*! ... */）—— 本页拒绝执行："
                            + "它的内容会被 MySQL 真的执行，与「只读」相悖。请改为普通查询。");
        }

        // 2) 首关键字白名单
        String first = firstKeyword(withoutTrailing);
        if (first == null) {
            throw BizException.badRequest("无法识别 SQL 的首关键字，拒绝执行");
        }
        if (!ALLOWED_FIRST_KEYWORDS.contains(first)) {
            throw BizException.badRequest(
                    "只允许只读查询（" + String.join(" / ", ALLOWED_FIRST_KEYWORDS)
                            + "），当前是：" + first);
        }

        // 3) 危险关键字黑名单（整词；先剥字面量，所以字符串里的词不会误报）
        //
        //    例外：`SHOW CREATE TABLE ...` 是纯只读语句，但含 CREATE。
        //    这里把这段**精确形状的前缀**挖掉再扫描，其余部分照旧受检。
        //    注意挖掉的只是前缀那几个词，后面的表名等仍在文本里参与检查，
        //    且分号检查已在第 1 步做过，所以 `SHOW CREATE TABLE t; DROP ...` 仍会被拦。
        String upper = withoutTrailing.toUpperCase(Locale.ROOT);
        String scanTarget = upper;
        Matcher showCreate = SHOW_CREATE_PREFIX.matcher(withoutTrailing);
        if (showCreate.find()) {
            // 只把匹配到的前缀替换成等长空格，保留其余文本
            scanTarget = " ".repeat(showCreate.end())
                    + upper.substring(showCreate.end());
        }
        for (String bad : FORBIDDEN_KEYWORDS) {
            if (containsWholeWord(scanTarget, bad)) {
                throw BizException.badRequest(
                        "SQL 含被禁止的关键字「" + bad + "」—— 本页只允许只读查询。"
                                + "（写操作请走对应的业务页面；若确需改数据请联系 DBA）");
            }
        }

        // ⚠️ 关键：返回的必须是**原始 SQL**，不是上面那份「剥掉字面量」的检查文本。
        //
        // 曾经这里返回了 withoutTrailing（剥掉字面量的版本），后果是：
        //   SELECT 'HELLO' AS note          -> 返回空字符串
        //   ... WHERE Name = '张国昌'        -> 一行都查不到
        // 也就是**所有字符串字面量都被静默清空**，查询结果错得毫无提示。
        // 实测就是这样发现的：`SELECT * FROM T_User WHERE Name='张国昌'` 返回 0 行。
        //
        // 剥字面量只应用于**校验**（避免 'DROP TABLE x' 误报、避免注释藏关键字），
        // 真正执行的一定是用户写的原文。所以这里对**原文**做与检查文本相同的末尾裁剪。
        String normalized = trimTrailingSemicolon(rawSql);
        log.info("只读 SQL 校验通过：{}", abbreviate(normalized));
        return new Validation(normalized);
    }

    /** 去掉首尾空白与**末尾**那一个分号（用户习惯性输入），保留原文其余部分 */
    private static String trimTrailingSemicolon(String rawSql) {
        String s = rawSql == null ? "" : rawSql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim();
    }

    /* ============================== 内部实现 ============================== */

    /**
     * 剥掉字符串字面量与注释。
     *
     * <p>这一步是**必须**的，两个方向都会出错：
     * <ul>
     *   <li>不剥字面量：{@code SELECT 'DROP TABLE x'} 会被误判成含 DROP；</li>
     *   <li>不剥注释：把关键字藏进注释里就能绕过黑名单
     *       （虽然 MySQL 会忽略普通注释，但攻击者也可能用
     *       <b>可执行注释</b>这种形态 —— 所以注释必须一起去掉）。
     *       注：此处刻意不写出可执行注释的字面写法，
     *       因为它含有会提前结束本 Javadoc 的字符序列（见下方 stripLiteralsAndComments）。</li>
     * </ul>
     *
     * <p>支持的形式：
     * <ul>
     *   <li>单引号 / 双引号字符串（含双写转义与反斜杠转义）</li>
     *   <li>反引号标识符（不剥，但要正确跳过，避免里面的分号被误判）</li>
     *   <li>行注释（两个连字符 或 井号）、块注释</li>
     * </ul>
     * 剥掉的字符替换成空格（而不是删除），以免把两个词粘成一个。
     *
     * <p>⚠️ <b>本注释里不能出现块注释的结束符号</b> —— 它会把 Javadoc 提前截断，
     * 后面的说明会变成代码，编译直接报一堆「非法字符」。
     * 这个坑在写本类时真实踩到过一次。
     */
    static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);

            // 行注释 --
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int j = sql.indexOf('\n', i);
                i = j < 0 ? n : j + 1;
                out.append(' ');
                continue;
            }
            // 行注释 #
            if (c == '#') {
                int j = sql.indexOf('\n', i);
                i = j < 0 ? n : j + 1;
                out.append(' ');
                continue;
            }
            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int j = sql.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                out.append(' ');
                continue;
            }
            // 单引号 / 双引号字符串
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;   // 反斜杠转义
                        continue;
                    }
                    if (d == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2; // '' 双写转义
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("''");
                continue;
            }
            // 反引号标识符：保留内容（它是表名/列名，要参与首关键字判断的是关键字不是它）
            // 但必须整体跳过，避免里面的 ; 被误判
            if (c == '`') {
                i++;
                int start = i;
                while (i < n && sql.charAt(i) != '`') {
                    i++;
                }
                out.append(sql, start, Math.min(i, n));
                i = Math.min(i + 1, n);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 取第一个「词」（字母组成的 token），并转大写 */
    static String firstKeyword(String strippedSql) {
        Matcher m = Pattern.compile("[A-Za-z_]+").matcher(strippedSql);
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
    }

    /**
     * 整词匹配（不区分大小写由调用方处理）。
     *
     * <p>必须是整词：否则 {@code CREATED_AT} 里的 {@code CREATE}、
     * 或列名 {@code update_time} 里的 {@code UPDATE} 会误报，
     * 让正常查询用不了。
     */
    static boolean containsWholeWord(String upperSql, String keyword) {
        // 关键字里可能有空格（如 "FOR UPDATE" / "LOAD DATA"），逐词判断
        String[] parts = keyword.split("\\s+");
        if (parts.length == 1) {
            return Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(parts[0]) + "(?![A-Z0-9_])")
                    .matcher(upperSql).find();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append("\\s+");
            }
            sb.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile("(?<![A-Z0-9_])" + sb + "(?![A-Z0-9_])")
                .matcher(upperSql).find();
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }
}
