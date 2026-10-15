package com.qianlong.cloud.admin.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 指标趋势模块（{@code /api/monitor/series}）的出入参。
 *
 * <h2>⚠️ 这份数据为什么「一半是真历史、一半是当前快照」</h2>
 * 现场**没有时序库**（Redis 也不保存指标历史），所以能画什么完全取决于
 * 「这个指标在 Redis 里有没有可回溯的痕迹」：
 *
 * <table border="1">
 *   <tr><th>指标</th><th>有无历史</th><th>原因</th></tr>
 *   <tr>
 *     <td>条目到达速率 / 累计条目数</td>
 *     <td><b>有，且是真实历史</b></td>
 *     <td>Stream 的条目 ID 是**毫秒时间戳**，保留中的条目本身就是到达记录 ——
 *         用 {@code XRANGE} 按时间窗分组即可还原</td>
 *   </tr>
 *   <tr>
 *     <td>消费组 lag / pending</td>
 *     <td><b>没有</b></td>
 *     <td>{@code XINFO GROUPS} 只给**当前快照**，Redis 不保存历史值</td>
 *   </tr>
 * </table>
 *
 * <p>因此本接口对「没有历史」的指标**不编曲线**，
 * 而是返回当前值 + {@code historyAvailable=false} + 原因说明，
 * 由页面如实呈现。拿演示数据凑一条平滑曲线出来，会让人以为
 * 「lag 在过去一小时里是这么变化的」—— 那是捏造的运维依据。
 */
public final class MonitorDtos {

    private MonitorDtos() {
    }

    /** 一个时序点。字段名与前端 {@code TimeSeriesPoint} 一致。 */
    public record TimeSeriesPoint(long time, double value) {
    }

    /**
     * 趋势序列。
     *
     * @param metric    指标键（与请求一致，便于前端核对）
     * @param label     中文名
     * @param unit      单位说明（如「条/秒」）
     * @param points    数据点。**无历史时为空数组**，页面据此显示空态而不是假曲线
     * @param historyAvailable 是否**真实历史**
     * @param currentValue     当前值（即便没有历史也一定给得出，取自实时快照）
     * @param source    数据来源说明（鼠标悬停时显示）
     * @param note      需要向用户说明的限制（无历史的原因等）
     * @param windowMin 时间窗（分钟）
     * @param bucketSec 每个点代表多少秒
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TimeSeriesView(
            String metric,
            String label,
            String unit,
            List<TimeSeriesPoint> points,
            boolean historyAvailable,
            Double currentValue,
            String source,
            String note,
            int windowMin,
            int bucketSec) {
    }

    /** 可选的指标（供页面渲染选项；也便于排查「键写错了没有」） */
    public record MetricOption(String key, String label, String unit, boolean historyAvailable) {
    }
}
