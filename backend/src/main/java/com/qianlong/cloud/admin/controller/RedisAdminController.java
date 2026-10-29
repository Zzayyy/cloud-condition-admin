package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisInstanceInfoView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisInstanceView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisKeyDetailView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.RedisPubSubChannelView;
import com.qianlong.cloud.admin.dto.RedisAdminDtos.ScanResult;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.RedisAdminService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Redis 管理接口（{@code /api/storage/redis/*}）。
 *
 * <p>与前端 {@code storageApi} 的 Redis 部分对应。数据来自现场两个实例的
 * {@code INFO}、{@code SCAN} 与键内容读取。
 *
 * <h2>⚠️ 三条安全线（本模块最容易出事的地方）</h2>
 * <ol>
 *   <li><b>只用 SCAN，绝不用 KEYS。</b>现场 137 db0 的
 *       {@code DataHub_req_stream} 有 <b>1400 万条</b>条目；
 *       {@code KEYS *} 会把实例阻塞住、影响正在跑的生产流。</li>
 *   <li><b>服务端强制条数上限，接口里没有「取全部」的选项</b>
 *       （{@code SCAN_HARD_LIMIT}）。被截断时如实返回 {@code truncated=true}，
 *       不静默截断 —— 否则会被当成「这个 db 就这么多键」。</li>
 *   <li><b>删除键是高危操作</b>：单独要求 {@code action:storage-redis:delete-key}
 *       权限点，且无论成功失败都写审计。</li>
 * </ol>
 *
 * <p><b>本模块不读 MySQL</b>（那是 {@code /storage/mysql/*}，尚未实现），
 * 也不碰 Redis 的写命令（除删除键外）。
 */
@RestController
@RequestMapping("/storage/redis")
@RequiredArgsConstructor
public class RedisAdminController {

    /** 菜单权限：Redis 管理页（读接口都要求它） */
    private static final String MENU_STORAGE_REDIS = "menu:storage-redis";

    /** 删除键的权限点（已在 T_AdminRolePermission 里存在，标记为高危） */
    private static final String PERM_DELETE_KEY = "action:storage-redis:delete-key";

    private final RedisAdminService redisAdminService;
    private final AuditService auditService;

    /**
     * 实例清单。
     *
     * <p>返回**配置里出现过的每台主机**（去重），而不是每个 (host,db) 组合，
     * 也不是前端 mock 里硬编码的两个 —— 实例增减时不用改前端。
     *
     * <p>⚠️ 这里的 {@code id} **就是主机地址**（如 {@code 192.168.1.136}）：
     * 管理页要浏览整台实例的所有 db，所以按主机定位；
     * 而业务侧的 {@code app.redis.instances} 是「业务要读的 (host,db) 组合」，
     * 两者用途不同。下面接口的 {@code instanceId} 参数接收的正是这个主机地址
     * （**参数名保持 {@code instanceId} 不变**，与前端 {@code endpoints.ts} 一致）。
     */
    @SaCheckPermission(MENU_STORAGE_REDIS)
    @GetMapping("/instances")
    public ApiResult<List<RedisInstanceView>> instances() {
        return ApiResult.ok(redisAdminService.listInstances());
    }

    /** 实例概览（INFO 常用字段 + 各 db 键数） */
    @SaCheckPermission(MENU_STORAGE_REDIS)
    @GetMapping("/info")
    public ApiResult<RedisInstanceInfoView> info(@RequestParam("instanceId") String host) {
        return ApiResult.ok(redisAdminService.instanceInfo(host));
    }

    /**
     * 扫描键（分页 + 关键字/类型/TTL 过滤）。
     *
     * <p>后端用 SCAN 并有硬上限；返回里的 {@code truncated} 字段
     * 明确告知「是否只扫了一部分」，{@code dbSize} 给出该 db 的真实键数，
     * 便于判断「列表不全」还是「本来就这么少」。
     */
    @SaCheckPermission(MENU_STORAGE_REDIS)
    @GetMapping("/keys")
    public ApiResult<ScanResult> keys(@RequestParam("instanceId") String host,
                                      @RequestParam(defaultValue = "0") int db,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) Boolean hasTtl,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer pageSize) {
        return ApiResult.ok(redisAdminService.scanKeys(
                host, db, keyword, type, hasTtl,
                page == null ? 1 : page,
                pageSize == null ? 15 : pageSize));
    }

    /** 单个键的内容（按类型返回不同结构，页面据此选渲染方式） */
    @SaCheckPermission(MENU_STORAGE_REDIS)
    @GetMapping("/key")
    public ApiResult<RedisKeyDetailView> keyDetail(@RequestParam("instanceId") String host,
                                                   @RequestParam(defaultValue = "0") int db,
                                                   @RequestParam String key) {
        return ApiResult.ok(redisAdminService.keyDetail(host, db, key));
    }

    /**
     * Pub/Sub 频道（含订阅者数）。
     *
     * <p>订阅者数是判断「消息有没有人在听」的关键 ——
     * 光看频道名看不出来。现场 136 有 14 个频道、137 一个都没有。
     */
    @SaCheckPermission(MENU_STORAGE_REDIS)
    @GetMapping("/channels")
    public ApiResult<List<RedisPubSubChannelView>> channels(@RequestParam("instanceId") String host) {
        return ApiResult.ok(redisAdminService.pubsubChannels(host));
    }

    /**
     * 删除键（**高危，不可撤销**）。
     *
     * <p>权限点 {@code action:storage-redis:delete-key} ——
     * 这是本模块唯一的写操作，也是唯一需要额外权限的动作。
     * 权限点只列真正危险的动作，避免管理员图省事全勾上而失去意义。
     *
     * <p>审计里记的是「谁 + 哪个实例/db + 哪个键 + 实际删掉几个」，
     * **成功与失败都写**（失败同样要留痕：有人试过删那个键）。
     *
     * <p>返回值是实际删除的键数：0 表示键本来就不存在 ——
     * **不假装成功**，前端据此如实提示。
     */
    @SaCheckPermission({MENU_STORAGE_REDIS, PERM_DELETE_KEY})
    @DeleteMapping("/key")
    public ApiResult<Long> deleteKey(@RequestParam("instanceId") String host,
                                     @RequestParam(defaultValue = "0") int db,
                                     @RequestParam String key,
                                     @CurrentAdminUser AdminPrincipal me,
                                     HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        String target = host + "/db" + db + " key=" + key;
        try {
            long deleted = redisAdminService.deleteKey(host, db, key);
            auditService.record(me.username(), PERM_DELETE_KEY, target, true,
                    deleted > 0 ? "DEL 成功，删除 " + deleted + " 个键" : "键不存在，未产生写入",
                    ip, AuditService.costOf(start));
            return ApiResult.ok(deleted);
        } catch (RuntimeException ex) {
            // 失败也要留痕：有人试过删这个键
            auditService.record(me.username(), PERM_DELETE_KEY, target, false,
                    ex.getMessage(), ip, AuditService.costOf(start));
            throw ex;
        }
    }
}
