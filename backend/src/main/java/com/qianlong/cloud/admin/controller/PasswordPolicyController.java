package com.qianlong.cloud.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.SystemDtos.PasswordPolicyDto;
import com.qianlong.cloud.admin.service.AuditService;
import com.qianlong.cloud.admin.service.PasswordPolicyService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 密码策略接口（{@code /api/system/password-policy}）。
 *
 * <p>前端只用它做<b>即时校验提示</b>；真正的判定在后端
 * （{@link PasswordPolicyService#validatePassword}）。前端注释里已写明这一点。
 *
 * <p>读取要求 {@code menu:system-user}：只有「管理用户与权限」页要用它渲染
 * 「还剩几次锁定」等提示；修改才要求 {@code action:system-user:manage}
 * （策略影响所有后台账号）。
 */
@RestController
@RequestMapping("/system/password-policy")
@RequiredArgsConstructor
public class PasswordPolicyController {

    /** 菜单权限：管理用户与权限页 */
    private static final String MENU_SYSTEM_USER = "menu:system-user";

    /** 策略变更影响所有后台账号，归到「管理后台账号」这一权限点下 */
    private static final String ACTION_MANAGE = "action:system-user:manage";

    private final PasswordPolicyService policyService;
    private final AuditService auditService;

    @SaCheckPermission(MENU_SYSTEM_USER)
    @GetMapping
    public ApiResult<PasswordPolicyDto> get() {
        return ApiResult.ok(policyService.getPolicy());
    }

    /** 保存策略。审计记下改了什么，但不记任何密码内容（这里本来也没有密码）。 */
    @SaCheckPermission({MENU_SYSTEM_USER, ACTION_MANAGE})
    @PutMapping
    public ApiResult<PasswordPolicyDto> save(@CurrentAdminUser AdminPrincipal me,
                                             @RequestBody PasswordPolicyDto payload,
                                             HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String ip = AuditService.clientIp(request);
        try {
            PasswordPolicyDto saved = policyService.savePolicy(payload);
            auditService.success(me.username(), ACTION_MANAGE, "T_AdminPasswordPolicy",
                    "更新密码策略：长度≥" + saved.minLength()
                            + "，失败 " + saved.maxFailedAttempts() + " 次锁定 " + saved.lockMinutes() + " 分钟"
                            + "，有效期 " + saved.expireDays() + " 天"
                            + "，历史 " + saved.historySize() + " 条",
                    ip, start);
            return ApiResult.ok(saved);
        } catch (RuntimeException ex) {
            auditService.failure(me.username(), ACTION_MANAGE, "T_AdminPasswordPolicy",
                    ex.getMessage(), ip, start);
            throw ex;
        }
    }
}
