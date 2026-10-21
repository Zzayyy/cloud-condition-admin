package com.qianlong.cloud.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 条件单写操作配置（{@code app.conditional.*}）。
 *
 * <h2>⭐ 语义（需求方 2026-10-21 明确）</h2>
 * <pre>
 *   改本地（Redis strategy-* + MySQL T_Conditional）—— <b>必做的主路径</b>
 *   下发 setByStrategy 通知策略平台 —— <b>附加动作</b>，有编号就发、没有就跳过
 * </pre>
 * 原话：「不管是否连上对应的策略服务器，只要后台发了改状态的申请就要改状态
 * （REDIS+MYSQL）」「只是如果连上策略服务器还要顺带通知一下策略服务器」。
 *
 * <p>所以 {@code strategyServerID = -1}（账号未连策略服务器）**不是错误**，
 * 只是「不需要通知」。此时结果 {@code outcome = local_only}，
 * 属于**正常路径**，不是降级。
 *
 * <h2>⚠️ 为什么总开关默认关</h2>
 * 写条件单**不是改库**，是改客户正在运行的条件单状态 —— 改错了会直接影响交易。
 * 所以 {@code enabled=false} 时一个字节都不写。另外两道闸只约束**下发**
 * （本地改照做）：
 * <ul>
 *   <li>{@code allowedServerIds} 为空/不匹配 —— 不向该编号下发（本地改照做）</li>
 *   <li>{@code allowLivePlatform=false} —— 目标流上坐着真策略平台时不下发（本地改照做）</li>
 * </ul>
 *
 * <h2>协议事实（2026-10-21 现场实测，见 backend/README.md）</h2>
 * <pre>
 *   客户端 ──[DataHub_req_stream]──▶ 数据中台 ──[ST-&lt;编号&gt;]──▶ 策略平台
 * </pre>
 * <ul>
 *   <li>{@code remove}（删除）—— 现场 {@code ST-0} 流里有真实样本，
 *       形状是 {@code {"MsgType":8,"remove":{...}}}，**管理端直发 ST-&lt;编号&gt;}。</li>
 *   <li>{@code setByStrategy}（启停）—— 现场只在 {@code DataHub_req_stream} 里
 *       出现过（形状 {@code {"setByStrategy":{...}}}，**不带 MsgType**），
 *       从未进过 {@code ST-&lt;编号&gt;}；而它的成功回执在
 *       {@code ST-&lt;编号&gt;-reply} 里（{@code {"Err":0,"Msg":"set conditional mode success"}}）。
 *       所以启停走「入口流 + 路由字段」，回执读 {@code ST-&lt;编号&gt;-reply}。</li>
 *   <li>⚠️ 中台**不认识** {@code setByStrategy}：它会往
 *       {@code DataHub_reply_stream} 回一条 {@code json parse error}。
 *       那是**假失败** —— 策略平台其实执行成功了。
 *       所以回执<b>不能</b>读 {@code DataHub_reply_stream}。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.conditional")
public class ConditionalWriteProperties {

    /** 写操作总开关。默认关 —— 打开前请先看 {@code allowedServerIds} 的说明 */
    private boolean enabled = false;

    /**
     * 目标 Redis 实例 id（对应 {@code app.redis.instances} 里的一条，如 {@code 136-0}）。
     *
     * <p>必须是**真策略平台所在的那个库**：现场是 {@code 192.168.1.136 db0}
     * （{@code ST-0} / {@code ST-1} 的消费者名 = 流名本身，即真平台）。
     */
    private String instanceId = "136-0";

    /** 等回执的超时（毫秒）。超时 = 「已下发但没收到回执」，不等于失败 */
    private long replyTimeoutMs = 10_000L;

    /**
     * 允许**下发**到的策略服务器编号白名单（如 {@code ["0"]}）。
     *
     * <p><b>为空 = 一律不下发</b>（不是「不限制」）。
     * 理由：编号决定报文发到哪个策略平台，发错就是给别的客户改条件单。
     *
     * <p>⚠️ 它<b>只拦下发，不拦本地改</b>：本地状态照改（那是需求要求的主路径），
     * 只是不通知平台，结果为 {@code local_only} 并如实说明原因。
     */
    private List<String> allowedServerIds = new ArrayList<>();

    /**
     * 目标流上**坐着真策略平台**时是否仍然下发。
     *
     * <p>默认 false。判据：{@code XINFO CONSUMERS} 里存在「消费者名 = 流名本身」
     * 的消费者（真平台的特征；本项目的 mock 用 {@code ST-N-w0} 这种带后缀的名字）。
     * 往这种流里 XADD = 给真平台下发真实条件单，<b>可能触发真实交易</b>。
     */
    private boolean allowLivePlatform = false;

    /** 启停协议 {@code setByStrategy} 的 {@code Mode}：停止 */
    private int stopMode = 0;

    /** 启停协议 {@code setByStrategy} 的 {@code Mode}：运行 */
    private int runMode = 1;

    /** 目标流名里的编号占位（{@code ST-<编号>}） */
    private String streamPrefix = "ST-";

    /** 目标流的回执流后缀（{@code ST-<编号>-reply}） */
    private String replySuffix = "-reply";

    /** 入口流名（客户端 → 数据中台） */
    private String requestStream = "DataHub_req_stream";

    /** 入口流条目的 {@code server_type}（{@code ST} = 策略平台方向） */
    private String serverType = "ST";
}
