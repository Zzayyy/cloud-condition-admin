package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalPage;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalQuery;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalStats;
import com.qianlong.cloud.admin.dto.ConditionalDtos.ConditionalView;
import com.qianlong.cloud.admin.dto.ConditionalDtos.DailyCreatedRow;
import com.qianlong.cloud.admin.dto.ConditionalDtos.TypeCountRow;
import com.qianlong.cloud.admin.dto.ConditionalWriteDtos.ConditionalStatePayload;
import com.qianlong.cloud.admin.dto.ConditionalWriteDtos.ConditionalWriteResult;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.ConditionalDispatchService;
import com.qianlong.cloud.admin.service.ConditionalService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
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

    /** 写操作权限点（已存在，high 危险级） */
    private static final String PERM_WRITE = "action:conditional:write";

    private final ConditionalService conditionalService;
    private final ConditionalDispatchService dispatchService;
    private final AuditService auditService;
    private final com.qianlong.cloud.admin.config.ConditionalWriteProperties writeProperties;

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
    public ApiResult<ConditionalStats> stats() {
        // 全量口径：**故意不接收** source / keyword / type / mode ——
        // 卡片是"整个条件单池子的现状"，跟着 tab 变会让"运行中"变成 0。
        return ApiResult.ok(conditionalService.stats());
    }

    /**
     * 条件单**状态变更**（停止 / 运行 / 终止）。
     *
     * <p>走 {@code setByStrategy} 协议下发到策略平台，<b>等回执</b>才算数。
     * 直接改库或改 Redis 都不会让运行中的条件单真的停下来 ——
     * 那是策略平台在跑的。
     *
     * <p><b>三档结果</b>（{@code outcome}）：
     * <ul>
     *   <li>{@code success} —— 平台回执明确成功</li>
     *   <li>{@code business_failed} —— 收到了回执，但平台说失败</li>
     *   <li>{@code timeout} —— 已下发，没等到回执。
     *       <b>既不是成功也不是失败</b>，只是"不知道结果"。</li>
     * </ul>
     *
     * <p>权限：{@code action:conditional:write}（high 危险级）。
     * 成功与失败<b>都写审计</b> —— 失败也要留痕（「谁试过停哪张单但没成」必须查得到）。
     */
    @SaCheckPermission(PERM_WRITE)
    @PostMapping("/{id}/state")
    public ApiResult<ConditionalWriteResult> setState(@PathVariable Long id,
                                                      @RequestBody ConditionalStatePayload payload,
                                                      @CurrentAdminUser AdminPrincipal me,
                                                      HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        String target = "条件单 " + id;
        try {
            ConditionalWriteResult result = dispatchService.setState(id, payload.mode(), payload.serverId());
            // ⚠️ 本地（Redis + MySQL）在所有档位都已经改完了 —— 见 ConditionalWriteResult 的注释。
            // 所以这里的 success 字段表示「本地状态变更是否完成」，平台通知的状态写在 message 里。
            // 只有 business_failed 记失败：那是平台明确拒绝、两边已经不一致。
            String message = switch (result.outcome()) {
                case "success" -> "已改本地（" + result.localSync() + "）；已下发 "
                        + result.protocol() + " 协议，策略平台回执成功";
                case "local_only" -> "已改本地（" + result.localSync() + "）；未下发协议："
                        + result.note();
                case "timeout" -> "已改本地（" + result.localSync() + "）；已下发 "
                        + result.protocol() + " 协议但未收到回执（平台状态未知，可能不一致）";
                default -> "已改本地（" + result.localSync() + "）；策略平台回执失败："
                        + (result.message() == null ? "原因未给出" : result.message())
                        + "（两边可能不一致，需人工核对）";
            };
            auditService.record(me.username(), PERM_WRITE, target, "success".equals(result.outcome()),
                    message, ip, AuditService.costOf(start));
            return ApiResult.ok(result);
        } catch (RuntimeException ex) {
            auditService.record(me.username(), PERM_WRITE, target, false,
                    ex.getMessage(), ip, AuditService.costOf(start));
            throw ex;
        }
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
        /*
         * 把「写操作开关当前是开还是关」一并回传 ——
         * 否则运维只能"点了按钮才知道没开"，而错误提示出现在点击之后，
         * 很容易被当成"按钮坏了"。页面据此提前把按钮置灰并写明原因。
         */
        boolean writable = writeProperties.isEnabled();
        if (!writable) {
            return ApiResult.ok(new WriteStatus(
                    true,
                    "状态变更（停止 / 运行 / 终止）已实现，但**当前未启用**"
                            + "（app.conditional.enabled=false），因此按钮不可用。"
                            + "启用后：① 先改本地（Redis + MySQL）—— 必做；"
                            + "② 若有可用的策略服务器编号，再下发 setByStrategy 通知平台并等回执。",
                    PERM_WRITE,
                    false,
                    false));
        }
        return ApiResult.ok(new WriteStatus(
                true,
                "状态变更（停止 / 运行 / 终止）已实现。执行顺序："
                        + "① 先改本地（Redis strategy-* + MySQL T_Conditional）—— 必做；"
                        + "② 若有可用的策略服务器编号，再下发 setByStrategy 协议通知平台并等回执。"
                        + "strategyServerID = -1 表示该账号未连接策略服务器，此时只改本地、无需通知，"
                        + "结果为 local_only（正常路径）。"
                        + "不提供新增与删除：需求方明确「不允许删除，只允许停止、终止等状态操作」——"
                        + "删除不可逆，而状态变更可逆。"
                        + "Mode=3（结束）由策略平台在生命周期走完后写入，不接受人工设置。",
                PERM_WRITE,
                false,
                true));
    }

    /**
     * 写操作实现状态。
     *
     * @param implemented        状态变更是否已实现
     * @param reason             说明
     * @param requiredPermission 所需权限点
     * @param createSupported    是否支持新增（当前<b>不支持</b>）
     * @param enabled            <b>当前是否可写</b>（{@code app.conditional.enabled}）。
     *                           页面据此提前置灰按钮，而不是等点了才报错
     */
    public record WriteStatus(boolean implemented, String reason, String requiredPermission,
                              boolean createSupported, boolean enabled) {
    }
}
