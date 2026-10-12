package com.qianlong.cloud.admin.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qianlong.cloud.admin.dto.SystemDtos.AuditActionOption;
import com.qianlong.cloud.admin.entity.AdminAuditEntity;
import com.qianlong.cloud.admin.mapper.AdminAuditMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 操作审计写入。
 *
 * <p><b>为什么审计写入不参与业务事务：</b>审计是「证据」，业务失败时更要有记录
 * （比如「重置密码失败」本身就是要查的事）。如果审计写进业务事务，
 * 业务回滚会把审计一起回滚掉 —— 那就等于「失败的操作没发生过」，
 * 而这恰恰是最需要留痕的一类。所以这里用 {@code REQUIRES_NEW} 之外的做法更简单：
 * 由调用方在 try/catch 里显式记录成功或失败，不在同一个事务里回滚。
 *
 * <p>另外，审计写入本身失败**不能**让业务失败（比如审计表暂时不可写），
 * 所以这里吞掉异常并打日志 —— 但要打 error 级别，不能悄悄吞掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AdminAuditMapper auditMapper;
    private final PermissionCatalog catalog;

    /**
     * 记录一条审计。
     *
     * @param operator 操作人（后台账号登录名）
     * @param action   动作键，须与权限键同源；未知键<b>不拒绝但记 warning</b>
     *                 （拒绝会让业务因为「忘记登记动作字典」而失败，代价太大）
     * @param target   目标对象
     * @param success  是否成功
     * @param message  失败原因或补充说明
     * @param ip       来源 IP
     * @param costMs   耗时毫秒
     */
    public void record(String operator, String action, String target,
                       boolean success, String message, String ip, Integer costMs) {
        try {
            String module = catalog.actionModule(action);
            String level = catalog.actionLevel(action);
            if ("other".equals(module)) {
                log.warn("审计动作键未登记到字典，将按 module=other/level=medium 落库：{}", action);
            }
            AdminAuditEntity entity = new AdminAuditEntity();
            entity.setOperator(StringUtils.hasText(operator) ? operator : "unknown");
            entity.setAction(action);
            // 标签落库：历史记录的中文名不应该因为后来改了字典而漂移（审计是证据）
            entity.setActionLabel(catalog.actionLabel(action));
            entity.setModule(module);
            entity.setLevel(level);
            entity.setTarget(truncate(target, 255));
            entity.setResult(success ? "success" : "failure");
            entity.setMessage(truncate(message, 500));
            entity.setIp(truncate(ip, 45));
            entity.setCostMs(costMs);
            entity.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(entity);
        } catch (Exception ex) {
            // 审计写失败不能让业务跟着失败，但必须留下痕迹
            log.error("写审计失败 action={} operator={} → 业务已按自身结果返回", action, operator, ex);
        }
    }

    /** 成功审计 */
    public void success(String operator, String action, String target, String message, String ip, long startMs) {
        record(operator, action, target, true, message, ip, costOf(startMs));
    }

    /** 失败审计（**业务失败也必须留痕**，否则「谁试过什么」查不到） */
    public void failure(String operator, String action, String target, String message, String ip, long startMs) {
        record(operator, action, target, false, message, ip, costOf(startMs));
    }

    public static int costOf(long startMs) {
        return (int) Math.max(0, System.currentTimeMillis() - startMs);
    }

    public java.util.List<AuditActionOption> actions() {
        return catalog.auditActions();
    }

    /* ------------------------------ 请求上下文 ------------------------------ */

    /**
     * 取客户端真实 IP。
     *
     * <p>按 {@code X-Forwarded-For} → {@code X-Real-IP} → {@code remoteAddr} 的顺序取。
     * 本项目部署在 Nginx 之后，直接取 {@code remoteAddr} 会全都是网关地址，
     * 审计里的 IP 就失去意义了。
     */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // XFF 是逗号分隔的链路，第一个才是原始客户端
            String first = forwarded.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /** 从 User-Agent 里提炼一个可读的「客户端 / OS」，存进登录日志的 loginOS 列 */
    public static String clientOs(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (!StringUtils.hasText(ua)) {
            return null;
        }
        return truncate(ua.trim(), 200);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
