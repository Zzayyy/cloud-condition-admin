package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalPage;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalQuery;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalStats;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalView;
import com.qianlong.cloud.admin.dto.ConditionalDtos.DailyCreatedRow;
import com.qianlong.cloud.admin.dto.ConditionalDtos.TypeCountRow;
import com.qianlong.cloud.admin.service.ConditionalService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 条件单管理接口（{@code /api/strategy/conditionals}）。
 *
 * <p>与前端 {@code strategyApi} 对应。当前只实现**读**接口：
 * <ul>
 *   <li>{@code GET /strategy/conditionals} —— 列表（三源切换 + 筛选 + 分页）</li>
 *   <li>{@code GET /strategy/conditionals/{id}} —— 详情</li>
 *   <li>{@code GET /strategy/conditionals/stats/daily} —— 按天新增趋势</li>
 *   <li>{@code GET /strategy/conditionals/stats/types} —— 类型分布</li>
 * </ul>
 *
 * <p><b>数据只来自 MySQL</b> 的 {@code T_Conditional} / {@code T_Conditional_his} /
 * {@code V_Conditional} —— 这三张表现在都是 0 行，页面会如实显示为空
 * （详见 {@code ConditionalService} 的类注释：为什么不拿 Redis 的数据来填）。
 *
 * <h2>⚠️ 为什么没有实现启停 / 删除</h2>
 * 前端的 {@code setConditional} / {@code removeConditional} 至今仍是
 * 「提示后端接口尚未实现」—— 而**这两个操作不该做成简单的更新接口**：
 * 它们要走 {@code set} / {@code remove} 协议**下发到策略平台**
 * （经 Redis Stream 到数据中心，再由数据中心转给策略服务器），
 * 策略平台执行后才回执。直接改 MySQL 不会让运行中的条件单真的启停，
 * 只会造成「库里显示已停止、实际还在跑」这种最危险的不一致。
 *
 * <p>所以本模块**只提供读接口**，并且在 {@code /instructions} 里
 * 明确告知前端「写操作尚未实现及其原因」，而不是留一个能改库但改不对的接口。
 * 等现场确认下发链路（需要数据中心配合）后再补。
 *
 * <p>权限：只要求登录。将来补启停/删除时，应使用
 * {@code action:conditional:write} 权限点（该权限点已在 T_AdminRolePermission
 * 里存在，且标记为 high 危险级）。
 */
@RestController
@RequestMapping("/strategy/conditionals")
@RequiredArgsConstructor
public class ConditionalController {

    private final ConditionalService conditionalService;

    /**
     * 条件单列表。
     *
     * <p>{@code source} 取值 {@code running} / {@code history} / {@code all}，
     * 分别对应 {@code T_Conditional} / {@code T_Conditional_his} /
     * {@code V_Conditional}。数据只来自这三张表。
     */
    @SaCheckLogin
    @GetMapping
    public ApiResult<ConditionalPage> list(ConditionalQuery query) {
        return ApiResult.ok(conditionalService.listConditionals(query));
    }

    /** 条件单详情 */
    @SaCheckLogin
    @GetMapping("/{id}")
    public ApiResult<ConditionalView> detail(@PathVariable Long id) {
        ConditionalView view = conditionalService.getConditional(id);
        if (view == null) {
            throw BizException.notFound("条件单不存在：" + id);
        }
        return ApiResult.ok(view);
    }

    /** 按天新增趋势（趋势图用） */
    @SaCheckLogin
    @GetMapping("/stats/daily")
    public ApiResult<List<DailyCreatedRow>> daily(@RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        return ApiResult.ok(conditionalService.dailyCreated(from, to));
    }

    /** 类型分布（全量口径，不限于当前页） */
    @SaCheckLogin
    @GetMapping("/stats/types")
    public ApiResult<List<TypeCountRow>> typeDistribution() {
        return ApiResult.ok(conditionalService.typeDistribution());
    }

    /**
     * 条件单汇总（**全量口径**，卡片用）。
     *
     * <p>与列表同参数（source / keyword / type / mode），
     * 所以 {@code totalCount} 应与列表的 {@code total} 相等。
     */
    @SaCheckLogin
    @GetMapping("/stats/summary")
    public ApiResult<ConditionalStats> stats(ConditionalQuery query) {
        return ApiResult.ok(conditionalService.stats(query));
    }

    /**
     * 写操作现状说明（供前端与排查使用）。
     *
     * <p>做成接口而不是只写在文档里，是为了让「为什么这个按钮点了没用」
     * 在联调时一眼可见，而不是去翻代码。
     */
    @SaCheckLogin
    @GetMapping("/instructions")
    public ApiResult<WriteStatus> instructions() {
        return ApiResult.ok(new WriteStatus(
                false,
                "启停与删除需要走 set / remove 协议下发到策略平台（经 Redis Stream 到数据中心），"
                        + "直接改库不会让运行中的条件单真的启停，反而会造成"
                        + "「库里显示已停止、实际还在跑」的不一致。"
                        + "待现场确认下发链路后实现。",
                "action:conditional:write"));
    }

    /** 写操作实现状态 */
    public record WriteStatus(boolean implemented, String reason, String requiredPermission) {
    }
}
