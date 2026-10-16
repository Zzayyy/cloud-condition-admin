package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountLogStatView;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountQuery;
import com.qianlong.cloud.admin.dto.AccountDtos.AccountRow;
import com.qianlong.cloud.admin.dto.AccountDtos.LogQuery;
import com.qianlong.cloud.admin.dto.AccountDtos.RedisAccountView;
import com.qianlong.cloud.admin.dto.AccountDtos.UserLogDailyRow;
import com.qianlong.cloud.admin.dto.AccountDtos.UserLogView;
import com.qianlong.cloud.admin.service.AccountLogService;
import com.qianlong.cloud.admin.service.AccountService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;

/**
 * 账户与用户接口（{@code /api/account/*}）。
 *
 * <p>与前端 {@code accountApi} 一一对应，三个页面共用：
 * <ul>
 *   <li>用户账号 —— {@code listAccounts}（MySQL+Redis 合并）、{@code getRedisAccount}；</li>
 *   <li>签署与股东号 —— {@code listAccounts}（复用，按 signed 筛选）；</li>
 *   <li>登录日志 —— {@code listLogs}、{@code listLogStats}。</li>
 * </ul>
 *
 * <p><b>权限：只要求登录，不要求额外权限点。</b>
 * 本模块目前只读（没有写操作），因此没有 {@code action:account-*:*} 之类的权限点 ——
 * 权限点只列真正危险的动作，避免管理员图省事全勾上而失去意义。
 * 将来若加「重置账号密码」这类写操作，再引入独立权限点并写审计。
 *
 * <p>⚠️ 注意本模块**不返回 {@code T_User.Passwd}**：
 * 出参 DTO 里根本没有这个字段。
 */
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final AccountLogService accountLogService;

    /**
     * 用户账号列表（MySQL {@code T_User} 与 Redis {@code acc-*} 合并后返回）。
     *
     * <p>合并由后端做：两个源都要参与筛选（在线 / 已签署 / 渠道横跨两边），
     * 拆成两个接口会让前端无法正确分页与筛选。
     */
    @SaCheckLogin
    @GetMapping("/accounts")
    public ApiResult<PageResult<AccountRow>> listAccounts(AccountQuery query) {
        AccountService.MergedAccounts result = accountService.listAccounts(query);
        // total 用 service 返回的「筛选后总数」（不是两侧原始数量），
        // 否则会出现「筛选后只有 1 条，但表格说共 14 条，翻页翻出空白」
        return ApiResult.ok(PageResult.of(
                result.rows(), result.total(),
                query.pageOrDefault(), query.pageSizeOrDefault()));
    }

    /** 单个账号详情（含 MySQL 与 Redis 两侧合并结果） */
    @SaCheckLogin
    @GetMapping("/accounts/{account}")
    public ApiResult<AccountRow> getAccount(@PathVariable String account) {
        AccountRow row = accountService.getAccount(account);
        if (row == null) {
            throw BizException.notFound("账号不存在：" + account);
        }
        return ApiResult.ok(row);
    }

    /**
     * 账号汇总（**全量口径**，卡片用）。
     *
     * <p>与 {@code /accounts} 列表同筛选条件，`totalCount` 应等于列表 `total`。
     *
     * <p>口径：账号列表本身是「MySQL T_User 与 Redis acc-* 合并」的结果，
     * 所以这里的全量 = 合并并集经筛选后的全部行，与列表 total 同源。
     */
    @SaCheckLogin
    @GetMapping("/accounts/stats/summary")
    public ApiResult<AccountService.AccountStats> accountStats(AccountQuery query) {
        return ApiResult.ok(accountService.stats(query));
    }

    /** 账号在 Redis 中的实时态（acc-* 原始 JSON） */
    @SaCheckLogin
    @GetMapping("/redis/{account}")
    public ApiResult<RedisAccountView> getRedisAccount(@PathVariable String account) {
        RedisAccountView view = accountService.getRedisAccount(account);
        if (view == null) {
            // 如实回 404：这个账号在 Redis 侧**没有**实时对象。
            // 不能返回一个空壳对象 —— 那会让人以为「有对象但字段是空的」。
            throw BizException.notFound(
                    "该账号在 Redis 侧没有实时对象（acc-" + account + " 不存在）");
        }
        return ApiResult.ok(view);
    }

    /**
     * Redis 实例连接情况（非前端契约接口，供排查）。
     *
     * <p>存在的意义：账号页的 {@code hasRedis} 依赖 Redis 可达性。
     * 若某个实例连不上，页面上的 hasRedis 会偏低，需要有一个地方能确认
     * 「是账号真的没有实时对象」还是「我们没连上 Redis」。
     */
    @SaCheckLogin
    @GetMapping("/redis-instances")
    public ApiResult<AccountService.RedisInstanceStatus> redisInstances() {
        return ApiResult.ok(accountService.redisInstanceStatus());
    }

    /* ================================ 登录日志 ================================ */

    /** 登录日志明细（分页 + 账号 / 关键字 / 设备有无 / 时间范围筛选） */
    @SaCheckLogin
    @GetMapping("/logs")
    public ApiResult<PageResult<UserLogView>> listLogs(LogQuery query) {
        return ApiResult.ok(accountLogService.listLogs(query));
    }

    /**
     * 登录日志按账号聚合（「登录频次」视图）。
     *
     * <p>由后端 GROUP BY 一次算好；前端拿分页明细自己数只能得到当前页。
     */
    @SaCheckLogin
    @GetMapping("/logs/stats")
    public ApiResult<PageResult<AccountLogStatView>> listLogStats(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResult.ok(accountLogService.listLogStats(
                keyword,
                page == null ? 1 : page,
                pageSize == null ? 20 : pageSize));
    }

    /**
     * 按天登录日志量（趋势图）。
     *
     * <p>⚠️ 这条路由是**新加的**：`UserLogMapper.selectDaily` 与
     * `AccountLogService.daily` 早就写好了，但一直没挂到 controller 上，
     * 于是前端那个「近 14 天日志量」在真实态下永远是空图
     * （页面注释写的是「真实态暂无趋势接口，显示空态」—— 当时确实没有）。
     *
     * <p>与另外两个趋势接口（条件单 / 委托）形状一致：{@code date} + {@code count}。
     */
    @SaCheckLogin
    @GetMapping("/logs/daily")
    public ApiResult<List<UserLogDailyRow>> logDaily(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResult.ok(accountLogService.daily(from, to));
    }
}
