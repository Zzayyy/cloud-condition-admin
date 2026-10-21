package com.qianlong.cloud.admin.dto;

/**
 * 条件单写操作的出入参。
 *
 * <p><b>为什么要单独一个 DTO 文件</b>：写操作的返回结构与只读接口差很多
 * （要带回"发到哪条流、request_id 是什么、回执是什么"），
 * 塞进 {@code ConditionalDtos} 会让只读契约被写操作的细节污染。
 */
public final class ConditionalWriteDtos {

    private ConditionalWriteDtos() {
    }

    /**
     * 状态变更请求体（对应前端 {@code setConditional}）。
     *
     * @param mode     目标状态：0 停止 / 1 运行 / 2 终止（INVALID）
     * @param serverId 目标策略服务器编号；为 null 时后端依次尝试
     *                 「条件单元素自带的」→「配置的默认值」。
     *                 <b>都拿不到就如实报错</b>（现场元素里的编号全是 -1，
     *                 那是"尚未分配"的哨兵值，不是可用编号）。
     */
    public record ConditionalStatePayload(Integer mode, String serverId) {
    }

    /**
     * 操作结果。
     *
     * <h2>⭐ 本地（Redis + MySQL）<b>永远都改</b>，四档说的是"通知策略平台"的结果</h2>
     * 需求方口径：不管是否连上策略服务器，申请一到就改本地；连上了才顺带通知平台。
     * <ul>
     *   <li>{@code success} —— 本地已改 + 平台回执明确成功</li>
     *   <li>{@code local_only} —— 本地已改 + <b>无需/未下发通知</b>
     *       （账号未连策略服务器 / 编号不在白名单 / 真平台闸未放行）。
     *       这是<b>正常路径</b>，不是降级。</li>
     *   <li>{@code timeout} —— 本地已改 + 已下发但没等到回执。
     *       平台状态未知，<b>可能不一致</b>。</li>
     *   <li>{@code business_failed} —— 本地已改 + 平台回执失败
     *       （现场实测过 {@code ErrID=-5 ref already inserted}）。
     *       <b>两边已经不一致</b>，必须人工核对。</li>
     * </ul>
     */
    public record ConditionalWriteResult(
            /** 条件单号 */
            String ref,
            /** 用的哪个协议：remove / setByStrategy */
            String protocol,
            /** 目标策略服务器编号 */
            String serverId,
            /** 报文发到了哪条流 */
            String targetStream,
            /** 本次请求的 request_id（回执按它匹配） */
            String requestId,
            /** 写进流里的条目 id（排查用） */
            String entryId,
            /** 三档判定：success / business_failed / timeout */
            String outcome,
            /** 回执里的错误码（无回执为 null） */
            Integer errCode,
            /** 回执原文 / 说明 */
            String message,
            /** 回执报文原文（排查用） */
            String replyRaw,
            /** 条件单来自哪个实例 */
            String sourceInstance,
            /** 补充提示（如"目标流上坐着真策略平台"） */
            String note,
            /** 是否真的下发了协议（{@code false} = 只改了本地） */
            boolean dispatched,
            /** 本地同步结果（redis / mysql 分别怎么写的），如 {@code redis=ok；mysql=ok（1 行）} */
            String localSync) {
    }
}

