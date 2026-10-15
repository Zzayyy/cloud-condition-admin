package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubEntryPage;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubNodePage;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubStreamView;
import com.qianlong.cloud.admin.dto.DatahubDtos.DatahubTraceView;
import com.qianlong.cloud.admin.dto.DatahubDtos.StreamQuery;
import com.qianlong.cloud.admin.service.DatahubService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 数据中台接口（{@code /api/datahub/*}）。
 *
 * <p>与前端 {@code datahubApi} 一一对应，「数据中台」与「消息总线」两页共用
 * （后者用 {@code listStreams} / {@code listStreamEntries}）。
 *
 * <h2>⚠️ 本模块的三条如实说明（都写进了响应里，不只是文档）</h2>
 * <ol>
 *   <li><b>「节点」是 Redis 实例，不是中台实例。</b>中台在 Redis 侧没有节点清单键，
 *       所以一行 = 一台实例；「在线」指该实例上近期收到过中台心跳，
 *       <b>不等于</b>「这台机器上跑着中台」。meta 里的 {@code note} 会把这句
 *       回给页面显示。</li>
 *   <li><b>{@code online} 是三态。</b>{@code null} 表示**观测不到**
 *       （订阅关闭 / 连接失败 / 还没收到第一条），<b>不是离线</b>。
 *       对专门看「活着吗」的页面，假的「全离线」比诚实的「未知」危险得多。</li>
 *   <li><b>流条目只在尾部窗口内取。</b>现场入口流 1400 万条，
 *       全量拉取要么超时要么拖垮 Redis。响应里的 {@code truncatedWindow}
 *       说明「只查了最近 N 条」——<b>「没找到」不等于「不存在」</b>。</li>
 * </ol>
 *
 * <p><b>权限</b>：只要求登录。本模块**全部只读**（没有任何写接口），
 * 因此没有独立权限点 —— 权限点只列真正危险的动作。
 */
@RestController
@RequestMapping("/datahub")
@RequiredArgsConstructor
public class DatahubController {

    private final DatahubService datahubService;

    /**
     * 中台实例清单（一行 = 一台 Redis 实例）。
     *
     * <p>附带各 db 的键数与 {@code unique_id} 水位、stream 规模、频道数，
     * 以及该实例上的心跳状态。
     */
    @SaCheckLogin
    @GetMapping("/nodes")
    public ApiResult<DatahubNodePage> listNodes(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResult.ok(datahubService.listNodes(page, pageSize));
    }

    /**
     * Pub/Sub 频道总览（含订阅者数）。
     *
     * <p>订阅者数是判断「消息有没有人在听」的关键：现场 136 有 14 个频道、
     * 137 一个都没有 —— 这个差异本身就是要看见的信息。
     *
     * <p>⚠️ 返回分成两部分，都是为了消除**观测者效应**（本模块常驻订阅了
     * {@code datahub_beat_1}，而 {@code PUBSUB CHANNELS} 只列有订阅者的频道）：
     * <ul>
     *   <li>{@code channels} —— 真实存在的频道，订阅者数**已扣除我们自己那一份**；</li>
     *   <li>{@code selfOnlyChannels} —— **只因为我们订阅才出现**的频道（如现场 137），
     *       单列出来而不是混进列表，也不是悄悄丢掉。</li>
     * </ul>
     */
    @SaCheckLogin
    @GetMapping("/channels")
    public ApiResult<DatahubService.ChannelScan> listChannels() {
        return ApiResult.ok(datahubService.listChannels());
    }

    /**
     * 消息总线总览：跨实例、跨 db 的 stream 清单与消费组积压。
     *
     * <p>返回的是**整个列表**（不在这里分页）：页面要用它算「无消费组的流」
     * 与「有积压的流」这类跨行的结论，先分页会导致统计只覆盖当前页。
     * 现场流的总数在百级（137 db0 有 116 条），一次返回的代价可接受；
     * 每条的长度已由服务端的扫描上限兜住。
     */
    @SaCheckLogin
    @GetMapping("/streams")
    public ApiResult<List<DatahubStreamView>> listStreams(StreamQuery query) {
        return ApiResult.ok(datahubService.listStreams(query));
    }

    /**
     * 某个流的条目（分页；**只在尾部窗口内**）。
     *
     * <p>路径参数是流名（可能含 {@code -} 等字符，前端已 encodeURIComponent）。
     * {@code window} 是尾部窗口大小，服务端有硬上限，传再大也没用。
     */
    @SaCheckLogin
    @GetMapping("/streams/{key}/entries")
    public ApiResult<DatahubEntryPage> listStreamEntries(
            @PathVariable String key,
            @RequestParam(required = false) String instance,
            @RequestParam(required = false) Integer db,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer window) {
        return ApiResult.ok(datahubService.listStreamEntries(key, instance, db, page, pageSize, window));
    }

    /**
     * 按 {@code request_id} 串联一次请求（入口 → 下发 → 回执）。
     *
     * <p>⚠️ 只在各流的尾部窗口内查找（见类注释第 3 条）。
     * 返回的 {@code searched} 会列出「在哪些流的多少条里找过」，
     * 便于判断「确实没有」还是「窗口太小没覆盖到」。
     */
    @SaCheckLogin
    @GetMapping("/trace")
    public ApiResult<DatahubTraceView> trace(
            @RequestParam String requestId,
            @RequestParam(required = false) Integer window) {
        return ApiResult.ok(datahubService.trace(requestId, window));
    }
}
