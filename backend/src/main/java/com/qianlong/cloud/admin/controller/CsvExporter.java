package com.qianlong.cloud.admin.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditLogView;

import lombok.extern.slf4j.Slf4j;

/**
 * 审计 CSV 导出。
 *
 * <p>三条刻意的做法：
 * <ol>
 *   <li><b>写 BOM（{@code \uFEFF}）</b>：Excel 打开无 BOM 的 UTF-8 CSV 会中文乱码 ——
 *       用户拿到一份乱码文件，等同于导出失败；</li>
 *   <li><b>用 {@code \r\n} 换行</b>：CSV 规范（RFC 4180）如此，也是 Excel 的预期；</li>
 *   <li><b>文件名严格白名单 + 必须落在导出目录内</b>：文件下载接口最常见的漏洞就是
 *       路径穿越（{@code ?file=../../etc/passwd}）。这里两层防护 ——
 *       正则只放行 {@code [A-Za-z0-9._-]}，并且在解析后又校验一次规范化后的路径
 *       仍在导出目录之下。</li>
 * </ol>
 */
@Slf4j
public final class CsvExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件名白名单：字母/数字/点/下划线/连字符。{@code ..} 会被拦下（含点但会连续出现，正则虽允许点，下面还有目录校验兜底） */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");

    /** 导出目录：系统临时目录下的固定子目录，避免写进应用工作目录污染部署包 */
    private static final Path EXPORT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "cca-audit-export");

    private static final String[] HEADER = {
            "id", "时间", "操作人", "动作键", "动作名", "模块", "级别", "目标", "结果", "耗时(ms)", "IP", "说明"
    };

    private CsvExporter() {
    }

    /** 导出结果：文件名 + 可直接下载的相对地址 */
    public record ExportFile(String filename, String url) {
    }

    /**
     * 把审计行写成 CSV 文件。
     *
     * @return 文件名与下载地址（相对地址，前端拼当前站点即可下载）
     */
    public static ExportFile writeAuditCsv(List<AuditLogView> rows) {
        StringBuilder sb = new StringBuilder();
        // BOM：没有它 Excel 会把 UTF-8 中文显示成乱码
        sb.append('\uFEFF');
        sb.append(String.join(",", HEADER)).append("\r\n");
        for (AuditLogView r : rows) {
            sb.append(csvCell(r.id()))
                    .append(',').append(csvCell(r.createdAt()))
                    .append(',').append(csvCell(r.operator()))
                    .append(',').append(csvCell(r.action()))
                    .append(',').append(csvCell(r.actionLabel()))
                    .append(',').append(csvCell(r.module()))
                    .append(',').append(csvCell(levelLabel(r.level())))
                    .append(',').append(csvCell(r.target()))
                    .append(',').append(csvCell("success".equals(r.result()) ? "成功" : "失败"))
                    .append(',').append(csvCell(r.costMs()))
                    .append(',').append(csvCell(r.ip()))
                    .append(',').append(csvCell(r.message()))
                    .append("\r\n");
        }

        String filename = "audit-logs-" + STAMP.format(LocalDateTime.now()) + ".csv";
        try {
            Files.createDirectories(EXPORT_DIR);
            Path target = resolveSafely(filename);
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
            log.info("审计导出文件已生成：{}（{} 行）", target, rows.size());
        } catch (IOException ex) {
            // 生成失败就如实报错，绝不回一个「下载 404」的地址糊弄前端
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "导出文件生成失败：" + ex.getMessage());
        }
        String url = "/api/system/audit-logs/export/download?file=" + filename;
        return new ExportFile(filename, url);
    }

    /** 读取已生成的导出文件（带路径穿越防护） */
    public static ResponseEntity<Resource> readExport(String filename) {
        Path target = resolveSafely(filename);
        if (!Files.isRegularFile(target)) {
            throw BizException.notFound("导出文件不存在或已过期：" + filename
                    + "（导出文件保存在服务端临时目录，重启后可能被清理，请重新导出）");
        }
        try {
            long size = Files.size(target);
            InputStream in = Files.newInputStream(target);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .contentLength(size)
                    .body(new InputStreamResource(in));
        } catch (IOException ex) {
            throw new BizException(com.qianlong.cloud.admin.common.ErrorCode.INTERNAL,
                    "读取导出文件失败：" + ex.getMessage());
        }
    }

    /**
     * 解析出安全的绝对路径。
     *
     * <p>两层防护：先按白名单校验文件名，再校验规范化后的路径确实位于导出目录之内。
     * 任一层不通过都直接报错 —— 不静默替换成别的文件名（那会掩盖攻击尝试）。
     */
    private static Path resolveSafely(String filename) {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            throw BizException.badRequest("非法的文件名：" + filename);
        }
        Path base = EXPORT_DIR.toAbsolutePath().normalize();
        Path target = base.resolve(filename).normalize();
        if (!target.startsWith(base)) {
            // 命中这里说明有人尝试路径穿越（如 file=..%2F..%2Fetc%2Fpasswd）
            log.warn("检测到疑似路径穿越的导出文件请求：{}", filename);
            throw BizException.badRequest("非法的文件名：" + filename);
        }
        return target;
    }

    /** CSV 单元格转义：含逗号/引号/换行时用双引号包裹，内部引号翻倍 */
    private static String csvCell(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private static String levelLabel(String level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case "high" -> "高危";
            case "medium" -> "中";
            case "low" -> "低";
            default -> level;
        };
    }

    /** 供测试：导出目录 */
    static Path exportDir() {
        return EXPORT_DIR;
    }

    /** 供测试：把某个文件从别处复制进导出目录（不用于生产路径） */
    static void copyForTest(Path source, String filename) throws IOException {
        Files.createDirectories(EXPORT_DIR);
        Files.copy(source, resolveSafely(filename), StandardCopyOption.REPLACE_EXISTING);
    }
}
