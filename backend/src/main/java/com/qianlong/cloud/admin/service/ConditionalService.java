package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalListMeta;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalPage;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalQuery;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalView;
import com.qianlong.cloud.admin.dto.ConditionalDtos.DailyCreatedRow;
import com.qianlong.cloud.admin.dto.ConditionalDtos.TypeCountRow;
import com.qianlong.cloud.admin.entity.ConditionalEntity;
import com.qianlong.cloud.admin.mapper.ConditionalMapper;

import lombok.RequiredArgsConstructor;

/**
 * 条件单查询 —— <b>只读 MySQL 的「三张表 + 一个视图」</b>。
 *
 * <h2>三个数据源</h2>
 * <ul>
 *   <li>{@code running} → {@code T_Conditional}（运行中的条件单）</li>
 *   <li>{@code history} → {@code T_Conditional_his}（历史条件单）</li>
 *   <li>{@code all} → {@code V_Conditional}（前两张表的并集视图）</li>
 * </ul>
 *
 * <h2>⚠️ 为什么这里**不**读 Redis（曾经读过，已移除）</h2>
 * 现场这三张表都是 0 行，而 Redis {@code strategy-*} 里有 60 多条条件单，
 * 字段与表结构一一对应。我一开始做成「两侧合并」，想着
 * 「至少页面上有数据可看」—— <b>那是错的</b>：
 *
 * <ol>
 *   <li><b>它让页面的读数失去了意义。</b>页面标题与菜单写的是
 *       「条件单管理 / T_Conditional」，用户看到 58 条会认为
 *       「T_Conditional 里有 58 条」。而事实是这张表**一条都没有**。
 *       运维据此判断「库里数据正常」，就永远不会去查为什么没落库。</li>
 *   <li><b>Redis 侧根本说不清是哪一份。</b>现场三个实例/db 都有
 *       {@code strategy-*}（136 db0 有 3 条、136 db1 有 55 条、
 *       137 db1 有 4 条），还有 4 条同一个 {@code id} 在两个实例上各存一份。
 *       合并时的「取第一个」是配置顺序决定的，页面无法表达这件事 ——
 *       用户看到的数字既不是某个实例的真实条数，也不是可靠的去重结果。</li>
 * </ol>
 *
 * <p><b>空表是可接受的、也是正确的呈现</b>：它如实反映「这张表没有数据」，
 * 而这本身就是一个需要现场关注的信号（README 里记着这个待确认事项）。
 * 用别处的数据把空表填满，只会把这个信号掩盖掉。
 *
 * <p>Redis 侧的 {@code strategy-*} 归「服务器管理」类页面去读更合适
 * （那里本来就在展示 Redis 的实时对象与流），不该混进「业务数据 / 对应一张
 * MySQL 表」的查询页里。
 */
@Service
@RequiredArgsConstructor
public class ConditionalService {

    private final ConditionalMapper conditionalMapper;

    /** 查询条件单（三源之一 + 筛选 + 分页） */
    public ConditionalPage listConditionals(ConditionalQuery query) {
        ConditionalSource source = ConditionalSource.of(query.getSource());
        String keyword = trimToNull(query.getKeyword());

        long total = conditionalMapper.countPage(
                source.table(), keyword, query.getType(), query.getMode());

        int page = query.pageOrDefault();
        int pageSize = query.pageSizeOrDefault();
        long offset = (long) (page - 1) * pageSize;

        List<ConditionalView> rows = conditionalMapper.selectPage(
                        source.table(), keyword, query.getType(), query.getMode(), offset, pageSize)
                .stream().map(ConditionalService::toView).toList();

        ConditionalListMeta meta = new ConditionalListMeta(
                source.code(), source.table(), source.label(), total);

        return new ConditionalPage(rows, total, page, pageSize, meta);
    }

    /**
     * 单条详情。
     *
     * <p>依次在运行中、历史、并集视图里找 —— 详情接口不带 {@code source}，
     * 前端从列表点进来时并不知道这条在哪张表里
     * （列表页的「全部」数据源本身就是并集视图）。
     */
    public ConditionalView getConditional(Long id) {
        if (id == null) {
            return null;
        }
        for (ConditionalSource source : List.of(
                ConditionalSource.RUNNING, ConditionalSource.HISTORY, ConditionalSource.ALL)) {
            ConditionalEntity entity = conditionalMapper.selectByIdIn(source.table(), id);
            if (entity != null) {
                return toView(entity);
            }
        }
        return null;
    }

    /**
     * 按天新增趋势。
     *
     * <p>口径是 {@code V_Conditional}（并集视图），按 {@code AddTime} 分组。
     * 表为空时返回空列表 —— 前端图表会显示「暂无数据」，
     * 不用别处的数据充数（前端 README 第 4 条明确要求这一点）。
     */
    public List<DailyCreatedRow> dailyCreated(String from, String to) {
        LocalDateTime f = TimeParams.parse(from, "from");
        LocalDateTime t = TimeParams.parse(to, "to");
        List<DailyCreatedRow> out = new ArrayList<>();
        for (ConditionalMapper.DayCountRow row : conditionalMapper.selectDaily(
                ConditionalSource.ALL.table(), f, t)) {
            out.add(new DailyCreatedRow(row.day(), row.cnt() == null ? 0L : row.cnt()));
        }
        return out;
    }

    /**
     * 类型分布（全量口径，不限于当前页）。
     *
     * <p>只统计主表与历史表，**不含并集视图** —— 视图是前两张表的并集，
     * 一起统计会把每条条件单算两遍。
     */
    public List<TypeCountRow> typeDistribution() {
        java.util.Map<Integer, Long> counts = new java.util.TreeMap<>();
        for (ConditionalSource source : List.of(
                ConditionalSource.RUNNING, ConditionalSource.HISTORY)) {
            for (ConditionalEntity e : conditionalMapper.selectPage(
                    source.table(), null, null, null, 0, MAX_STATS_ROWS)) {
                if (e.getType() != null) {
                    counts.merge(e.getType(), 1L, Long::sum);
                }
            }
        }
        List<TypeCountRow> out = new ArrayList<>();
        counts.forEach((type, cnt) -> out.add(new TypeCountRow(type, typeLabel(type), cnt)));
        return out;
    }

    /**
     * 统计类查询的取数上限。
     *
     * <p>类型分布是「全量口径」，但也不能无上限地把整表读进内存。
     * 现场三张表都是 0 行，永远不会触发；真涨上去之后这个上限会让统计
     * 只覆盖前 N 条 —— 那时应该改成在 SQL 里 {@code GROUP BY Type} 让数据库算，
     * 而不是把明细捞回来。这一点留在此处备忘。
     */
    private static final int MAX_STATS_ROWS = 5000;

    /* ================================ 映射 ================================ */

    private static ConditionalView toView(ConditionalEntity e) {
        return new ConditionalView(
                e.getId(), e.getAccount(), e.getUserAccount(), e.getStrategy(),
                DateTimes.format(e.getAddTime()), DateTimes.format(e.getRunTime()),
                DateTimes.format(e.getCloseTime()), e.getRunCount(),
                DateTimes.format(e.getBeginTime()), DateTimes.format(e.getEndTime()),
                e.getStatus(), e.getFlag(), e.getDescription(), e.getValidity(),
                DateTimes.format(e.getModifyTime()), DateTimes.format(e.getTriggeredTime()),
                e.getReason(), e.getMode(), e.getType(), e.getName());
    }

    /** 与前端 {@code CONDITIONAL_TYPES} 一致的标签 */
    private static String typeLabel(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case 0 -> "保留";
            case 1 -> "开仓条件单";
            case 2 -> "平仓条件单";
            case 3 -> "按合约止盈止损";
            case 4 -> "按标的止盈止损";
            default -> "类型 " + type;
        };
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
