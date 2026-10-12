package com.qianlong.cloud.admin.controller;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.qianlong.cloud.admin.dto.AdminPrincipal;
import com.qianlong.cloud.admin.common.BizException;
import com.qianlong.cloud.admin.common.ErrorCode;
import com.qianlong.cloud.admin.service.AdminUserService;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;

/**
 * 解析 {@link CurrentAdminUser}：把 Sa-Token 里的 loginId 换成完整的当前用户身份。
 *
 * <p>没有登录时会抛 {@code NOT_LOGIN}，由全局异常处理器转成前端能识别的 code。
 */
@Component
@RequiredArgsConstructor
public class CurrentAdminUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final AdminUserService adminUserService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdminUser.class)
                && AdminPrincipal.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (!StpUtil.isLogin()) {
            throw new BizException(ErrorCode.NOT_LOGIN, "登录状态已失效，请重新登录");
        }
        Integer userId = Integer.valueOf(String.valueOf(StpUtil.getLoginId()));
        AdminPrincipal principal = adminUserService.principalOf(userId);
        if (principal == null) {
            // 账号在会话有效期内被删除：立即踢下线，不能让「已删除的账号」继续操作
            StpUtil.logout();
            throw new BizException(ErrorCode.NOT_LOGIN, "账号不存在或已被删除，请重新登录");
        }
        return principal;
    }
}
