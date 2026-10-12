package com.qianlong.cloud.admin.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.util.StringUtils;

/**
 * 查询条件里的时间字符串 → {@link LocalDateTime}。
 *
 * <p><b>为什么不直接接受任意格式：</b>前端只会传 {@code yyyy-MM-dd HH:mm:ss}。
 * 若这里宽松解析（例如用 {@code LocalDateTime.parse} 默认 ISO），
 * 前端格式一变就会**静默解析失败并被当成「没传条件」**，从而返回全量数据 ——
 * 这类静默失效是本项目重点防范的对象（见前端 README 第 8 条）。
 *
 * <p>所以：能按约定格式解析就解析；传了但解析不了，**直接报错**而不是忽略。
 */
public final class TimeParams {

    private TimeParams() {
    }

    /**
     * @param raw  前端传的字符串
     * @param name 参数名，仅用于报错文案
     * @return 解析结果；未传或空白时返回 {@code null}（表示「不过滤」）
     * @throws BizException 传了值但不合格式
     */
    public static LocalDateTime parse(String raw, String name) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        // 只认约定的两种格式：datetime 与纯日期（纯日期按当天 00:00:00 处理）
        try {
            if (text.length() == 10) {
                return java.time.LocalDate.parse(text, DateTimes.DATE).atStartOfDay();
            }
            return LocalDateTime.parse(text, DateTimes.DATE_TIME);
        } catch (DateTimeParseException ex) {
            throw BizException.badRequest(
                    "参数 " + name + " 格式不正确，应为 yyyy-MM-dd HH:mm:ss，实际为：" + text);
        }
    }
}
