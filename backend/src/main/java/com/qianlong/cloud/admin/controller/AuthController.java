package com.qianlong.cloud.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.common.ApiResult;
import com.qianlong.cloud.admin.dto.SystemDtos.ChangePasswordPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginPayload;
import com.qianlong.cloud.admin.dto.SystemDtos.LoginResult;
import com.qianlong.cloud.admin.dto.SystemDtos.ProfileResult;
import com.qianlong.cloud.admin.service.AdminUserService;
import com.qianlong.cloud.admin.service.AuthService;

import cn.dev33.satoken.annotation.SaCheckLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 登录鉴权接口（{@code /api/system/auth/*}）。
 *
 * <p>与前端 {@code authApi} 一一对应：{@code login} / {@code logout} / {@code profile} /
 * {@code changePassword}。
 *
 * <p>登录与会话由 Sa-Token 承担（前端 axios 拦截器发 {@code Authorization: Bearer <token>}，
 * 与 {@code application.yml} 里的 {@code token-name}/{@code token-prefix} 对齐）。
 */
@RestController
@RequestMapping("/system/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AdminUserService adminUserService;

    /**
     * 登录。
     *
     * <p><b>不加 {@code @SaCheckLogin}</b>（这是登录入口本身）。
     * 失败时按 {@code LOGIN_ERROR_CODES} 返回 {@code code}，前端据此分派文案。
     *
     * <p>注意：这里**不区分**「账号不存在」与「密码错误」（同为 40101），
     * 也不返回账号是否存在 —— 否则就是一个账号枚举接口。
     */
    @PostMapping("/login")
    public ApiResult<LoginResult> login(@Valid @RequestBody LoginPayload payload,
                                        HttpServletRequest request) {
        return ApiResult.ok(authService.login(payload.username(), payload.password(), request));
    }

    /** 登出（只注销当前 token，不影响该账号在别处的会话） */
    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ApiResult.ok();
    }

    /**
     * 当前登录人的资料与权限键集合。
     *
     * <p>前端路由守卫在「有 token 但内存里没有身份」（刷新页面）时调用。
     * 返回的 {@code permissions} 是**实时从数据库读的**，所以改权限立即生效。
     */
    @SaCheckLogin
    @GetMapping("/profile")
    public ApiResult<ProfileResult> profile(@CurrentAdminUser AdminPrincipal me) {
        return ApiResult.ok(authService.profile(me.userId()));
    }

    /**
     * 修改自己的密码。
     *
     * <p>改密成功后后端会让该账号的已签发 token 失效（前端注释里写明了这一点），
     * 所以调用方需要重新登录 —— 这是刻意的：不能让旧会话继续持有已变更的凭证。
     */
    @SaCheckLogin
    @PostMapping("/password")
    public ApiResult<Void> changePassword(@CurrentAdminUser AdminPrincipal me,
                                          @Valid @RequestBody ChangePasswordPayload payload) {
        adminUserService.changeOwnPassword(me.userId(), payload.oldPassword(), payload.newPassword());
        return ApiResult.ok();
    }

    /** 当前登录人的权限键（调试/排查用，正式流程走 profile） */
    @SaCheckLogin
    @GetMapping("/permissions")
    public ApiResult<List<String>> permissions(@CurrentAdminUser AdminPrincipal me) {
        return ApiResult.ok(me.permissions());
    }
}
