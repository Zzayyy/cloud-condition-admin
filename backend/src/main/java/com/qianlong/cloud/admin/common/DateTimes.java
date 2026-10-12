package com.qianlong.cloud.admin.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间口径 —— 全站唯一来源。
 *
 * <p><b>为什么坚持 {@code yyyy-MM-dd HH:mm:ss} 这种定宽零填充格式：</b>
 * 前端的「时间范围筛选」是拿字符串直接比较的（见前端 README 第 8 条踩坑记录：
 * 传 ISO 或 {@code YYYY-M-D} 会**静默筛错**，37 行 vs 45 行，且不报错）。
 * 所以后端返回的时间、聚合用的日期都必须与此格式严格一致。
 */
public final class DateTimes {

    /** 接口出入参统一的时间格式（与前端 {@code fmt()} 完全一致） */
    public static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 聚合行里的日期（前端 {@code AuditDailyRow.date}） */
    public static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateTimes() {
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }

    public static String formatDate(LocalDateTime value) {
        return value == null ? null : DATE.format(value);
    }
}
