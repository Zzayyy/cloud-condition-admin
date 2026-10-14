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

    /**
     * 格式化 {@link java.time.LocalDate}（只有日期，没有时分秒）。
     *
     * <p>用于那些**列类型就是 {@code date}** 的表：{@code T_Order.OrderTime}、
     * {@code T_Order_his.OrderTime}、{@code T_Trade.TradeTime}、
     * {@code T_Trade_his.TradeTime}（现场实测均为 {@code date}）。
     *
     * <p>刻意输出 {@code yyyy-MM-dd} 而**不是**补成 {@code 00:00:00} ——
     * 补零会让页面显示「2026-06-04 00:00」，让人误以为真有这个精确时刻。
     * 库里只有日期，就只给日期。
     */
    public static String formatLocalDate(java.time.LocalDate value) {
        return value == null ? null : DATE.format(value);
    }
}
