package com.qianlong.cloud.admin.service;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.config.ConditionalWriteProperties;
import com.qianlong.cloud.admin.config.RedisProperties;
import com.qianlong.cloud.admin.dto.ConditionalWriteDtos.ConditionalWriteResult;
import com.qianlong.cloud.admin.service.ConditionalTargetLookup.ConditionalTarget;
import com.qianlong.cloud.admin.service.RedisStreamWriter.StreamEntry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 条件单状态变更的下发（走 {@code setByStrategy} 协议）。
 *
 * <h2>⚠️ 不做删除</h2>
 * 需求方明确「不允许删除，只允许停止、终止等状态操作」。
 * 删除不可逆（remove 协议一旦被策略平台执行就删掉了），
 * 状态变更是可逆的，风险低得多。
 *
 * <h2>协议（全部来自 2026-10-21 现场实测样本，不是照文档抄的）</h2>
 * <pre>
 *   请求报文（入口流与下发流里都是这个形状，【不带 MsgType】）：
 *     {"setByStrategy":{"Unique_Account":"010100011300_7_6","Mode":2,"Refs":["20261020000270"]}}
 *
 *   回执（在 ST-&lt;编号&gt;-reply 里）：
 *     {"Err":0,"Msg":"set conditional mode success","Results":[{"Ref":"20261020000270","Mode":2}]}
 * </pre>
 *
 * <h2>⚠️ 回执<b>不能</b>只读 {@code DataHub_reply_stream}</h2>
 * 现场实测：同一条 {@code setByStrategy} 在 {@code DataHub_reply_stream} 里
 * 收到的是 {@code {"Errmsg":"json parse error","ErrID":-8}} ——
 * <b>那是假失败</b>（中台不认识 setByStrategy，解析失败），
 * 而策略平台其实执行成功了（{@code ST-<编号>-reply} 里有
 * {@code set conditional mode success}）。
 * 所以本类<b>两个回执流都读</b>，按 {@code request_id} 匹配，谁先到用谁。
 *
 * <h2>⚠️ 目标编号查不到（当前现场的硬阻塞）</h2>
 * 条件单元素里的 {@code strategyServerID} 现场<b>60 条全是 -1</b>
 * （-1 = 尚未分配到编号的哨兵值），{@code acc-*.strategyserverid} 也是 -1/0。
 * 也就是说<b>「这条单该发给哪台策略平台」这个映射在现场并不存在</b>。
 * 本类的处理是：<b>如实报错</b>，不猜、不默认成 0（0 恰好是现场真平台所在的流）。
 * 编号由调用方显式给出（页面选 / 配置默认），且必须在白名单里。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConditionalDispatchService {

    /** 协议名（写进结果与审计） */
    private static final String PROTOCOL = "setByStrategy";

    private final ConditionalWriteProperties properties;
    private final RedisProperties redisProperties;
    private final RedisStreamWriter streamWriter;
    private final ConditionalTargetLookup targetLookup;
    private final com.qianlong.cloud.admin.mapper.ConditionalMapper conditionalMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** request_id 序号（同一毫秒内也要唯一） */
    private final AtomicLong seq = new AtomicLong();

    /**
     * 变更条件单状态。
     *
     * @param id       条件单号
     * @param mode     目标状态：0 停止 / 1 运行 / 2 终止（INVALID）
     * @param serverId 目标策略服务器编号；为 null 时依次尝试
     *                  「条件单元素自带的」→「配置的默认值」
     * @return 结果（三档判定）
     */
    public ConditionalWriteResult setState(Long id, Integer mode, String serverId) {
        if (!properties.isEnabled()) {
            throw BizException.badRequest(
                    "条件单写操作未启用（app.conditional.enabled=false）。"
                            + "启停会真正改变客户正在运行的条件单，需要现场明确开启。");
        }
        if (mode == null || (mode != 0 && mode != 1 && mode != 2)) {
            throw BizException.badRequest("mode 只能是 0（停止）/ 1（运行）/ 2（终止 INVALID），实际为：" + mode);
        }

        ConditionalTarget target = targetLookup.resolve(id);
        if (target == null) {
            throw BizException.notFound("条件单不存在：" + id);
        }
        // ---- 0. 状态机校验（必须在任何写动作之前）----
        // 只靠前端禁用按钮挡不住直接调接口，所以规则放这里。
        ensureTransitionAllowed(target.mode(), mode);

        RedisProperties.Instance instance = requireInstance();

        // ---- 1. 本地状态：**必做的主路径** ----
        // 需求方口径：「不管是否连上策略服务器，只要后台发了改状态的申请就要改状态
        // （REDIS+MYSQL）」—— 所以先改本地，再谈通知。
        LocalSync sync = syncLocal(target, mode);
        if (!sync.redisOk() && !sync.mysqlOk()) {
            // 两个都没改成 = 主路径失败，必须报错（不能假装成功）
            throw BizException.badRequest("改本地状态失败：" + sync.describe());
        }

        // ---- 2. 能不能通知策略平台？（附加动作，不是前提）----
        ServerTarget st = resolveServerId(target, serverId);
        if (st.serverId() == null) {
            // 账号未连接策略服务器（strategyServerID = -1）—— **正常路径**，不是降级
            return new ConditionalWriteResult(
                    target.ref(), "local-only", null, null, null, null,
                    "local_only", null,
                    "已改本地状态；未下发协议：" + st.reason(),
                    null, target.instanceDisplay(),
                    "该账号当前未连接策略服务器（strategyServerID=" + target.serverId()
                            + "），因此无需通知平台；本地状态已改。",
                    false, sync.describe());
        }

        String targetStream = properties.getStreamPrefix() + st.serverId();
        String replyStream = targetStream + properties.getReplySuffix();

        // 安全闸：目标流上坐着真策略平台时默认不发（但本地已经改了）
        String gateReason = checkSafetyGate(instance, targetStream);
        if (gateReason != null) {
            return new ConditionalWriteResult(
                    target.ref(), "local-only", st.serverId(), targetStream, null, null,
                    "local_only", null,
                    "已改本地状态；未下发协议：" + gateReason,
                    null, target.instanceDisplay(),
                    "⚠️ 未通知策略平台（" + gateReason + "），本地状态已改。",
                    false, sync.describe());
        }

        // ---- 3. 记下回执流的尾 id，等回执时从这里往后读 ----
        String replyTailId = tailId(instance, replyStream);
        String globalReplyTailId = tailId(instance, properties.getRequestStream());

        // ---- 4. 拼报文并下发 ----
        String requestId = nextRequestId();
        String task = buildTask(target, mode);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("request_id", requestId);
        fields.put("task", task);
        String entryId;
        try {
            entryId = streamWriter.xadd(instance.getHost(), instance.getDb(), targetStream, fields);
        } catch (RuntimeException ex) {
            // 下发失败不回滚本地 —— 需求是「本地必须改」，通知失败只影响一致性，
            // 所以如实报到 timeout 档并说明
            return new ConditionalWriteResult(
                    target.ref(), PROTOCOL, st.serverId(), targetStream, requestId, null,
                    "timeout", null,
                    "已改本地状态；下发失败：" + ex.getMessage(),
                    null, target.instanceDisplay(),
                    "⚠️ 本地已改，但报文没发出去 —— 平台状态未知，可能不一致。",
                    false, sync.describe());
        }

        // ---- 5. 等回执 ----
        Reply reply = awaitReply(instance, requestId, replyStream, replyTailId,
                properties.getRequestStream(), globalReplyTailId);

        String note = switch (reply.outcome()) {
            case "success" -> "本地已改，策略平台已回执成功";
            case "timeout" -> "⚠️ 本地已改，但未收到平台回执 —— 平台状态未知，可能不一致。";
            default -> "⚠️ 本地已改，但策略平台回执失败 —— 两边可能不一致，需要人工核对。";
        };

        return new ConditionalWriteResult(
                target.ref(),
                PROTOCOL,
                st.serverId(),
                targetStream,
                requestId,
                entryId,
                reply.outcome(),
                reply.errCode(),
                reply.message(),
                reply.raw(),
                target.instanceDisplay(),
                note,
                true,
                sync.describe());
    }

    /* ================================ 内部 ================================ */

    /**
     * 状态机：哪些状态转换是合法的。
     *
     * <h2>⚖️ 规则（需求方 2026-10-21 明确）</h2>
     * <pre>
     *   当前 null（未知） → 0 / 1 / 2   允许（状态不明时允许设置）
     *   当前 0（停止）    → 1 / 2       允许
     *   当前 1（运行）    → 0 / 2       允许
     *   当前 2（终止）    → 一律拒绝     ← 终止不可恢复
     *   当前 3（结束）    → 一律拒绝     ← 生命周期终态
     *   目标 == 当前      → 拒绝（无需变更，别发无意义的报文）
     * </pre>
     *
     * <h2>⚠️ 为什么 2 和 3 是终态，而且不可互换</h2>
     * <ul>
     *   <li><b>2（失效 / 终止）</b>：人为结束。需求方明确"终止真的不可恢复"。</li>
     *   <li><b>3（结束）</b>：<b>策略平台</b>在生命周期走完后自己写的
     *       （触发执行完毕、或过了 {@code EndTime}）。
     *       它是"结果"不是"指令" —— 人工设 3 会让
     *       「正常走完」和「被人强行结束」在审计上再也分不开，
     *       而那正是复盘时要回答的第一个问题。现场 {@code setByStrategy}
     *       的样本里也从没出现过 {@code Mode:3}。</li>
     * </ul>
     *
     * <p><b>为什么必须在后端做</b>：前端禁用按钮只是 UI，
     * 直接 POST 接口照样能改。状态机是数据完整性问题，不是展示问题。
     */
    private void ensureTransitionAllowed(Integer from, int to) {
        // 目标值只允许 0/1/2 —— 3（结束）由策略平台自己写，不接受人工设置
        if (to != 0 && to != 1 && to != 2) {
            throw BizException.badRequest(
                    "不支持把状态改成「" + modeName(to) + "」（Mode=" + to + "）。"
                            + "3（结束）是策略平台在生命周期走完后自己写的终态，不提供人工设置。");
        }
        if (from == null) {
            return;
        }
        if (from == 3) {
            throw BizException.badRequest(
                    "条件单已结束（Mode=3）。这是策略平台在生命周期走完后写入的终态，不可再改 ——"
                            + "它是「结果」不是「指令」，人工改它会让「正常走完」与「被强行结束」分不开。");
        }
        if (from == 2) {
            throw BizException.badRequest(
                    "条件单已终止（Mode=2），终止不可恢复。");
        }
        if (from == to) {
            throw BizException.badRequest(
                    "条件单当前已是「" + modeName(from) + "」状态，无需变更。");
        }
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case 0 -> "停止";
            case 1 -> "运行";
            case 2 -> "终止（失效）";
            case 3 -> "结束";
            default -> "未知(" + mode + ")";
        };
    }

    /**
     * 定目标策略服务器编号。
     *
     * <p>顺序：调用方给的 → 条件单元素自带的（必须 &gt;= 0）。
     * <b>都没有就返回 null + 原因</b>，由调用方决定"报错"还是"降级为只改本地"。
     * 现场元素里的编号全是 -1（尚未分配），此时猜一个（比如 0）
     * 会打到真策略平台所在的流上，那是最危险的方向 —— 所以这里不猜。
     */
    private ServerTarget resolveServerId(ConditionalTarget target, String requested) {
        String candidate = null;
        String from = null;
        if (requested != null && !requested.isBlank()) {
            candidate = requested.trim();
            from = "请求指定";
        } else if (target.hasUsableServerId()) {
            candidate = target.serverId().trim();
            from = "条件单自带";
        }
        if (candidate == null) {
            return new ServerTarget(null, "查不到条件单 " + target.ref() + " 所属的策略服务器编号："
                    + "Redis 里的 strategyServerID 是 " + target.serverId()
                    + "（-1 表示尚未分配），acc-* 的 strategyserverid 也是无效值");
        }
        if (!candidate.matches("\\d+")) {
            return new ServerTarget(null, "策略服务器编号必须是数字，实际为：" + candidate);
        }
        // 白名单：为空 = 全部拒绝（不是"不限制"）
        List<String> allowed = properties.getAllowedServerIds();
        if (allowed == null || allowed.isEmpty()) {
            return new ServerTarget(null,
                    "未配置允许下发的策略服务器编号（app.conditional.allowed-server-ids 为空 = 全部拒绝）");
        }
        if (!allowed.contains(candidate)) {
            return new ServerTarget(null, "策略服务器编号 " + candidate
                    + " 不在允许列表 " + allowed + " 内（来源：" + from + "）");
        }
        return new ServerTarget(candidate, null);
    }

    /**
     * 把状态同步到本地（Redis 实时态 + MySQL 归档）。
     *
     * <h2>⭐ 这是主路径，不是"回执成功后的补充"</h2>
     * 需求方口径：「不管是否连上策略服务器，只要后台发了改状态的申请就要改状态
     * （REDIS+MYSQL）」。所以本方法是<b>无条件执行</b>的 ——
     * 不看回执、不看有没有编号、不看安全闸。
     *
     * <p>两者都写不成功时，由调用方报错（主路径失败不能假装成功）；
     * 只成功一边时照常返回，由 {@code localSync} 字段如实说明。
     *
     * <h2>⚠️ Redis 侧必须"先定位、再写、再校验"</h2>
     * {@code Strategy} 是数组，{@code JSON.SET} 按下标定位。并发插入/删除会让下标漂移，
     * 所以写完要<b>重新读一次</b>，确认键名、下标、状态三者都对得上 ——
     * 否则宁可报失败，也不能默认成功（那会改错单子）。
     */
    private LocalSync syncLocal(ConditionalTarget target, int mode) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        // ⚠️ 必须写到「键实际所在的那个实例」，不是配置里的那个：
        // 现场 strategy-* 在 136 db0/db1 与 137 db1 都有多个副本，
        // 往一个本来没有这个键的库上 JSON.SET 会因路径不存在而失败。
        String host = target.patchHost();
        int db = target.patchDb();
        boolean redisOk = false;
        String redisMsg;
        if (!target.patchableInRedis()) {
            redisMsg = "Redis 侧未定位到（strategy-" + target.uniqueAccount() + " 里没有 "
                    + target.ref() + "），跳过";
        } else {
            try {
                streamWriter.patchConditionalMode(host, db,
                        target.strategyKey(), target.arrayIndex(), mode, now);
                // 写完校验：键、下标、状态三者都要对得上。
                // ⚠️ 只比 mode 不够 —— 若并发插入把下标挤走，mode 可能"碰巧"已经是目标值，
                //    那样会把"改错了单"当成成功。所以 strategyKey + arrayIndex 一起校验。
                // ⚠️⚠️ 必须比【Redis 的 mode】（redisMode），不能比 MySQL 的：
                //    syncLocal 是先写 Redis、后写 MySQL，此刻 MySQL 还是旧值，
                //    拿它来校验会 100% 判失败 → 每次都被回滚（踩过）。
                ConditionalTarget after = targetLookup.resolve(Long.valueOf(target.ref()));
                redisOk = after != null
                        && target.strategyKey().equals(after.strategyKey())
                        && after.arrayIndex() == target.arrayIndex()
                        && after.redisMode() != null
                        && after.redisMode() == mode;
                if (!redisOk) {
                    if (target.redisMode() != null) {
                        streamWriter.patchConditionalMode(host, db,
                                target.strategyKey(), target.arrayIndex(), target.redisMode(), now);
                        redisMsg = "已写入但回读校验不通过（下标可能已漂移），"
                                + "已回滚为原状态 " + target.redisMode();
                    } else {
                        redisMsg = "已写入但回读校验不通过（下标可能已漂移），"
                                + "且原状态未知、无法回滚 —— 需要人工核对";
                    }
                } else {
                    redisMsg = "ok";
                }
            } catch (Exception ex) {
                redisMsg = "失败：" + ex.getMessage();
            }
        }

        boolean mysqlOk = false;
        String mysqlMsg;
        try {
            int rows = conditionalMapper.updateMode(Long.valueOf(target.ref()), mode,
                    java.time.LocalDateTime.now());
            mysqlOk = rows > 0;
            mysqlMsg = rows > 0 ? "ok（" + rows + " 行）" : "库里没有这条单（0 行），未更新";
        } catch (Exception ex) {
            mysqlMsg = "失败：" + ex.getMessage();
        }
        return new LocalSync(redisOk, redisMsg, mysqlOk, mysqlMsg);
    }

    /** 目标 Redis 实例（配置里那条） */
    private RedisProperties.Instance requireInstance() {
        String id = properties.getInstanceId();
        for (RedisProperties.Instance i : redisProperties.getInstances()) {
            if (i.getId().equals(id)) {
                return i;
            }
        }
        throw BizException.badRequest("app.conditional.instance-id 指向的实例不存在：" + id);
    }

    /**
     * 安全闸：目标流上是否坐着<b>真策略平台</b>。
     *
     * <p>判据：消费者名 = 流名本身（真平台；本项目 mock 用 {@code ST-N-wN}）。
     * 往这种流里 XADD = 给真平台下发真实条件单，<b>可能触发真实交易</b>。
     *
     * @return {@code null} = 可以发；非空 = 不发的**原因**（调用方据此跳过下发，
     *         但<b>本地状态照改</b> —— 需求是「本地必须改」，通知是附加动作）
     */
    private String checkSafetyGate(RedisProperties.Instance instance, String targetStream) {
        List<RedisStreamWriter.ConsumerInfo> consumers =
                streamWriter.consumers(instance.getHost(), instance.getDb(), targetStream);
        for (RedisStreamWriter.ConsumerInfo c : consumers) {
            if (targetStream.equals(c.name())) {
                if (properties.isAllowLivePlatform()) {
                    return null;
                }
                return "目标流 " + targetStream + " 上有真策略平台在消费（消费者名 = 流名本身），"
                        + "按配置不向它下发（会真的改变客户的条件单、甚至触发交易）；"
                        + "确认要发请显式设置 app.conditional.allow-live-platform=true";
            }
        }
        return null;
    }

    /** 记下流当前的尾 id（等回执时从它之后读） */
    private String tailId(RedisProperties.Instance instance, String stream) {
        List<StreamEntry> tail = streamWriter.tail(instance.getHost(), instance.getDb(), stream, 1);
        return tail.isEmpty() ? "0-0" : tail.get(0).id();
    }

    /**
     * 拼 {@code setByStrategy} 报文。
     *
     * <p>形状照现场实测样本：{@code Unique_Account}（<b>带下划线</b>，
     * 与 {@code create}/{@code remove} 里的 {@code UniqueAccount} 不一样）、
     * {@code Mode}、{@code Refs}（<b>数组</b>，即使只改一张单也是数组）。
     */
    private String buildTask(ConditionalTarget target, int mode) {
        if (target.uniqueAccount() == null || target.uniqueAccount().isBlank()) {
            throw BizException.badRequest("条件单 " + target.ref()
                    + " 没有拼接账号（Unique_Account），无法组装报文。");
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode setByStrategy = root.putObject("setByStrategy");
        setByStrategy.put("Unique_Account", target.uniqueAccount());
        setByStrategy.put("Mode", mode);
        ArrayNode refs = setByStrategy.putArray("Refs");
        refs.add(target.ref());
        return root.toString();
    }

    /**
     * 生成 request_id。
     *
     * <p><b>格式不能改</b>：策略平台会<b>解析</b>这个字段，格式固定为
     * {@code <前缀>_<ip>_<epoch毫秒>_<序号>}（下划线分隔、后两段是数字）。
     * 踩坑记录（2026-09-28，现场实测）：曾把用例编号塞进去
     * （{@code STTEST_C201#1}），结果<b>平台读到第 1 条就再也不动了</b>
     * （{@code entries-read=1, lag=95}）。
     */
    private String nextRequestId() {
        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            ip = "127.0.0.1";
        }
        if (ip == null || ip.isBlank() || ip.contains("_")) {
            ip = "127.0.0.1";
        }
        return "ST_" + ip + "_" + System.currentTimeMillis() + "_" + seq.incrementAndGet();
    }

    /**
     * 等回执（两个流都读，按 request_id 匹配）。
     *
     * <p>用普通 {@code XREAD}，<b>不建消费组</b> —— 建组会把真平台的消息抢走
     * （Redis Streams 同组是负载均衡不是广播，抢了不报错，极难排查）。
     */
    private Reply awaitReply(RedisProperties.Instance instance, String requestId,
                             String replyStream, String replyTailId,
                             String globalReplyStream, String globalTailId) {
        long deadline = System.currentTimeMillis() + properties.getReplyTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            Reply r = pollReply(instance, requestId, replyStream, replyTailId);
            if (r != null) {
                return r;
            }
            Reply g = pollReply(instance, requestId, globalReplyStream, globalTailId);
            if (g != null) {
                return g;
            }
        }
        return new Reply("timeout", null,
                "已下发，但 " + properties.getReplyTimeoutMs() + " 毫秒内没收到策略平台回执（结果未知）",
                null);
    }

    /** 在一个流里找匹配 request_id 的回执；没找到返回 null */
    private Reply pollReply(RedisProperties.Instance instance, String requestId,
                            String stream, String fromId) {
        long remain = Math.max(500L, properties.getReplyTimeoutMs() / 4);
        List<StreamEntry> entries =
                streamWriter.readAfter(instance.getHost(), instance.getDb(), stream, fromId, remain);
        for (StreamEntry e : entries) {
            String rid = e.fields().get("request_id");
            if (!requestId.equals(rid)) {
                continue;
            }
            return parseReply(e.fields().get("task"));
        }
        return null;
    }

    /**
     * 解析回执报文。
     *
     * <p>现场有两种形态，都要认：
     * <pre>
     *   ST-&lt;编号&gt;-reply     : {"Err":0,"Msg":"set conditional mode success","Results":[...]}
     *   DataHub_reply_stream : {"Ref":"...","Errmsg":"insert success","ErrID":0}
     * </pre>
     * 成功 = 错误码为 0；<b>非 0 是业务失败</b>（现场实测过
     * {@code ErrID=-5 ref already inserted}、{@code ErrID=-8 json parse error}）。
     */
    private Reply parseReply(String task) {
        if (task == null || task.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(task);
            if (node == null || !node.isObject()) {
                return new Reply("business_failed", null, "回执不是 JSON 对象：" + task, task);
            }
            Integer code = null;
            if (node.hasNonNull("ErrID")) {
                code = node.get("ErrID").asInt();
            } else if (node.hasNonNull("Err")) {
                code = node.get("Err").asInt();
            }
            String message = node.hasNonNull("Errmsg")
                    ? node.get("Errmsg").asText()
                    : (node.hasNonNull("Msg") ? node.get("Msg").asText() : null);
            if (code == null) {
                return new Reply("business_failed", null,
                        "回执里没有错误码字段：" + task, task);
            }
            if (code == 0) {
                return new Reply("success", 0, message == null ? "策略平台已回执成功" : message, task);
            }
            return new Reply("business_failed", code,
                    message == null ? ("策略平台回执失败，错误码 " + code) : message, task);
        } catch (Exception ex) {
            return new Reply("business_failed", null, "回执解析失败：" + ex.getMessage(), task);
        }
    }

    /** 一条回执 */
    private record Reply(String outcome, Integer errCode, String message, String raw) {
    }

    /**
     * 目标策略服务器编号的解析结果。
     *
     * @param serverId 可用编号；{@code null} 表示查不到/不允许
     * @param reason   查不到的原因（给运维看，要能直接定位）
     */
    private record ServerTarget(String serverId, String reason) {
    }

    /**
     * 本地状态同步结果（Redis 实时态 + MySQL 归档）。
     *
     * <p>两边分别成败 —— <b>不能合成一个"成功"</b>：
     * 常见情况是 Redis 改到了、MySQL 里没有这条单（0 行），
     * 合成一个布尔值就会把"只改了一半"说成成功。
     */
    private record LocalSync(boolean redisOk, String redisMsg,
                             boolean mysqlOk, String mysqlMsg) {

        String describe() {
            return "redis=" + (redisOk ? "" : "未成功：") + redisMsg
                    + "；mysql=" + (mysqlOk ? "" : "未成功：") + mysqlMsg;
        }
    }
}

