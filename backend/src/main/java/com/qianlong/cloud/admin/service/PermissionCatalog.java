package com.qianlong.cloud.admin.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.dto.SystemDtos.AuditActionOption;

/**
 * 权限键与审计动作字典 —— 后端侧的唯一来源。
 *
 * <p>必须与前端 {@code src/config/permissions.ts} <b>逐字一致</b>：
 * <pre>
 *   menu:&lt;菜单 name&gt;        菜单权限，17 个（含「总览」自己）
 *   action:&lt;模块&gt;:&lt;动作&gt;    操作权限，7 个
 *   *                       通配符，超管专用
 * </pre>
 *
 * <p><b>为什么后端要维护这份清单，而不是「前端传什么就存什么」：</b>
 * 若后端不校验，前端（或任何人直接打接口）可以写进任意字符串，
 * 权限表里就会慢慢积累一批永远匹配不上的垃圾键；更糟的是若将来有人把
 * 判定逻辑写成前缀匹配（{@code menu:} 开头即放行），垃圾键会变成真实权限。
 * 所以新增权限点时必须两边同时改 —— 这是刻意的摩擦。
 *
 * <p><b>父级不隐式覆盖操作权限</b>（与前端 {@code hasPermission} 一致）：
 * 勾了 {@code menu:storage-redis} <b>不等于</b>拥有 {@code action:storage-redis:delete-key}，
 * 否则「能看到页面」就等于「能删键」，操作权限点就白加了。
 */
@Component
public class PermissionCatalog {

    /** 通配符：拥有全部权限 */
    public static final String WILDCARD = "*";

    /** 菜单权限：菜单 name → 页面标题（与前端 menu.ts 的 meta.title 一致） */
    private static final Map<String, String> MENUS = new LinkedHashMap<>();

    static {
        MENUS.put("dashboard", "总览");
        MENUS.put("server-trade", "委托服务器");
        MENUS.put("server-datahub", "数据中台");
        MENUS.put("server-strategy", "策略服务器");
        MENUS.put("server-stream", "消息总线");
        MENUS.put("conditional", "条件单管理");
        MENUS.put("order", "委托单管理");
        MENUS.put("deal", "成交回报管理");
        MENUS.put("strategy-log", "策略日志管理");
        MENUS.put("account-user", "用户账号");
        MENUS.put("account-sign", "签署与股东号");
        MENUS.put("account-log", "登录日志");
        MENUS.put("storage-redis", "Redis 管理");
        MENUS.put("storage-mysql", "数据库管理");
        MENUS.put("system-user", "管理用户与权限");
        MENUS.put("system-role", "角色与权限");
        MENUS.put("system-audit", "操作审计");
    }

    /** 操作权限字典：键 → (标签, 归属模块, 危险级别) */
    private record ActionDef(String label, String module, String level) {
    }

    private static final Map<String, ActionDef> ACTIONS = new LinkedHashMap<>();

    static {
        ACTIONS.put("action:conditional:write", new ActionDef("启停 / 删除条件单", "bizdata", "high"));
        ACTIONS.put("action:server-trade:toggle", new ActionDef("启停委托服务器", "server", "high"));
        ACTIONS.put("action:storage-redis:delete-key", new ActionDef("删除 Redis 键", "storage", "high"));
        ACTIONS.put("action:storage-mysql:readonly-sql", new ActionDef("执行只读 SQL", "storage", "medium"));
        ACTIONS.put("action:system-user:manage", new ActionDef("管理后台账号", "system", "high"));
        ACTIONS.put("action:system-role:manage", new ActionDef("管理角色与权限", "system", "high"));
        ACTIONS.put("action:system-audit:export", new ActionDef("导出审计日志", "system", "low"));
    }

    /**
     * 不属于权限点、但需要留痕的登记型审计动作。
     *
     * <p>它们没有对应的权限键（登录谁都得有、连接配置页已删），
     * 但审计里必须能看到 —— 所以单独列一份，而不是硬塞进权限字典。
     */
    private static final Map<String, ActionDef> AUDIT_ONLY_ACTIONS = new LinkedHashMap<>();

    static {
        AUDIT_ONLY_ACTIONS.put("system:connection", new ActionDef("修改连接配置", "system", "high"));
        AUDIT_ONLY_ACTIONS.put("system:login", new ActionDef("管理端登录", "system", "low"));
    }

    /** 全部合法权限键（不含通配符），共 17 + 7 = 24 个 */
    private static final Set<String> ALL_KEYS = new LinkedHashSet<>();

    static {
        MENUS.keySet().forEach(name -> ALL_KEYS.add("menu:" + name));
        ALL_KEYS.addAll(ACTIONS.keySet());
    }

    public Set<String> allPermissionKeys() {
        return Set.copyOf(ALL_KEYS);
    }

    public int permissionKeyCount() {
        return ALL_KEYS.size();
    }

    public boolean isKnownPermissionKey(String key) {
        return ALL_KEYS.contains(key);
    }

    public boolean isWildcard(String key) {
        return WILDCARD.equals(key);
    }

    public Map<String, String> menus() {
        return Map.copyOf(MENUS);
    }

    /**
     * 校验并规范化一份权限集合。
     *
     * @throws BizException 出现未知权限键（**不静默丢弃** —— 静默丢弃会让管理员
     *                      以为自己授了权，实际没授）
     */
    public List<String> normalize(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw BizException.badRequest("至少选择一个权限点：一个权限都没有的角色，登录后什么都看不到");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : permissions) {
            String key = raw == null ? "" : raw.trim();
            if (key.isEmpty()) {
                continue;
            }
            if (isWildcard(key) || isKnownPermissionKey(key)) {
                out.add(key);
            } else {
                throw BizException.badRequest("未知的权限键：" + key
                        + "。合法键为 menu:<菜单名> 或 action:<模块>:<动作>（见前端 src/config/permissions.ts）");
            }
        }
        if (out.isEmpty()) {
            throw BizException.badRequest("至少选择一个权限点：一个权限都没有的角色，登录后什么都看不到");
        }
        return List.copyOf(out);
    }

    /** 审计动作字典（供前端筛选下拉）：权限型动作 + 登记型动作 */
    public List<AuditActionOption> auditActions() {
        List<AuditActionOption> out = new java.util.ArrayList<>();
        ACTIONS.forEach((key, def) -> out.add(new AuditActionOption(key, def.label(), def.module(), def.level())));
        AUDIT_ONLY_ACTIONS.forEach((key, def) -> out.add(new AuditActionOption(key, def.label(), def.module(), def.level())));
        return out;
    }

    /** 某个动作键的中文名；未知动作返回键本身（不编造标签） */
    public String actionLabel(String action) {
        ActionDef def = ACTIONS.get(action);
        if (def == null) {
            def = AUDIT_ONLY_ACTIONS.get(action);
        }
        return def == null ? action : def.label();
    }

    /** 某个动作键的归属模块；未知动作返回 {@code "system"} 之外的兜底值由调用方决定 */
    public String actionModule(String action) {
        ActionDef def = ACTIONS.get(action);
        if (def == null) {
            def = AUDIT_ONLY_ACTIONS.get(action);
        }
        return def == null ? "other" : def.module();
    }

    /** 某个动作键的危险级别；未知动作按 medium（保守：不因为不认识就当成低危） */
    public String actionLevel(String action) {
        ActionDef def = ACTIONS.get(action);
        if (def == null) {
            def = AUDIT_ONLY_ACTIONS.get(action);
        }
        return def == null ? "medium" : def.level();
    }
}
