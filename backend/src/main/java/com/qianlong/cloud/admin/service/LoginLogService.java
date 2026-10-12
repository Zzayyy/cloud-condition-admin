package com.qianlong.cloud.admin.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.DateTimes;
import com.qianlong.cloud.admin.common.PageResult;
import com.qianlong.cloud.admin.common.TimeParams;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogQuery;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginLogView;
import com.qianlong.cloud.admin.entity.AdminLoginLogEntity;
import com.qianlong.cloud.admin.mapper.AdminLoginLogMapper;

import lombok.RequiredArgsConstructor;

/**
 * 管理端登录日志的查询。
 *
 * <p>⚠️ 这张表是 {@code T_AdminLoginLog}，<b>不是</b>云单客户的 {@code T_UserLog}
 * （后者 19 万行、{@code LogIP}/{@code LogMAC}/{@code LogOS} 三列全 NULL）。
 * 两条线在界面上分属「系统管理 / 操作审计」与「账户与用户 / 登录日志」，
 * 接口也刻意分开，避免有人把客户登录记录当成后台登录记录来查。
 */
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final AdminLoginLogMapper loginLogMapper;

    /** 记录一条登录日志（成功或失败都记） */
    public void record(String username, boolean success, String info, String ip, String os) {
        AdminLoginLogEntity entity = new AdminLoginLogEntity();
        entity.setUsername(username == null ? "unknown" : username);
        entity.setLoginTime(java.time.LocalDateTime.now());
        entity.setLoginIP(ip);
        entity.setLoginOS(os);
        entity.setSuccess(success);
        entity.setInfo(info);
        loginLogMapper.insert(entity);
    }

    public PageResult<LoginLogView> listLogs(LoginLogQuery query) {
        LambdaQueryWrapper<AdminLoginLogEntity> wrapper = Wrappers.lambdaQuery();

        String keyword = trimToNull(query.getKeyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(AdminLoginLogEntity::getUsername, keyword)
                    .or().like(AdminLoginLogEntity::getLoginIP, keyword)
                    .or().like(AdminLoginLogEntity::getLoginOS, keyword));
        }
        String username = trimToNull(query.getUsername());
        if (username != null) {
            wrapper.like(AdminLoginLogEntity::getUsername, username);
        }
        String ip = trimToNull(query.getIp());
        if (ip != null) {
            wrapper.like(AdminLoginLogEntity::getLoginIP, ip);
        }
        // 前端「结果」下拉取值为 'true' / 'false'（字符串）。用 Boolean 接收，
        // 无法识别的取值会直接报错而不是静默不过滤（前端 README 第 7 条记录过这个坑）。
        if (query.getSuccess() != null) {
            wrapper.eq(AdminLoginLogEntity::getSuccess, query.getSuccess());
        }
        var from = TimeParams.parse(query.getFrom(), "from");
        var to = TimeParams.parse(query.getTo(), "to");
        wrapper.ge(from != null, AdminLoginLogEntity::getLoginTime, from);
        wrapper.le(to != null, AdminLoginLogEntity::getLoginTime, to);

        boolean asc = "asc".equalsIgnoreCase(query.getSortOrder());
        String sortBy = trimToNull(query.getSortBy());
        if (sortBy == null || "loginTime".equals(sortBy)) {
            wrapper.orderBy(true, asc, AdminLoginLogEntity::getLoginTime);
        } else if ("username".equals(sortBy)) {
            wrapper.orderBy(true, asc, AdminLoginLogEntity::getUsername);
        } else {
            throw BizException.badRequest("不支持按 " + sortBy + " 排序");
        }
        wrapper.orderByDesc(AdminLoginLogEntity::getId);

        Page<AdminLoginLogEntity> page = loginLogMapper.selectPage(
                Page.of(query.pageOrDefault(), query.pageSizeOrDefault()), wrapper);

        List<LoginLogView> list = page.getRecords().stream().map(LoginLogService::toView).toList();
        return PageResult.of(list, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private static LoginLogView toView(AdminLoginLogEntity e) {
        return new LoginLogView(
                e.getId(), e.getUsername(), DateTimes.format(e.getLoginTime()),
                e.getLoginIP(), e.getLoginOS(), Boolean.TRUE.equals(e.getSuccess()), e.getInfo());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供测试与排查：最近 N 条 */
    public List<LoginLogView> recent(int limit) {
        return loginLogMapper.selectList(Wrappers.<AdminLoginLogEntity>lambdaQuery()
                        .orderByDesc(AdminLoginLogEntity::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .sorted(Comparator.comparing(AdminLoginLogEntity::getId).reversed())
                .map(LoginLogService::toView)
                .toList();
    }
}
