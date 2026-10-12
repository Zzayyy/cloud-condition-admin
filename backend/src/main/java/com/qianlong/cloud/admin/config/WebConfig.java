package com.qianlong.cloud.admin.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.qianlong.cloud.admin.controller.CurrentAdminUserArgumentResolver;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;

/**
 * Web 层配置：注册 Sa-Token 注解校验拦截器 + 当前登录人参数解析器。
 *
 * <h2>⚠️ 必须注册 {@link SaInterceptor}，否则所有鉴权注解都是摆设</h2>
 *
 * <p>Sa-Token 的 {@code @SaCheckLogin} / {@code @SaCheckPermission} 是**注解**，
 * 需要由 {@code SaInterceptor} 在请求进入 Controller 前读取并执行校验。
 * 而 {@code sa-token-spring-boot3-starter} 的自动配置
 * （{@code META-INF/spring/...AutoConfiguration.imports}）只注册了
 * {@code SaBeanRegister} / {@code SaBeanInject} 等 Bean，
 * <b>不会</b>自动注册这个拦截器 —— 它需要使用者自己 {@code addInterceptors}。
 *
 * <p><b>后果有多严重：</b>漏注册时，注解**不报任何错**，请求照样打到 Controller 上，
 * 鉴权彻底不生效。本项目实测踩到过：只读观察者（viewer 角色，没有任何
 * {@code action:system-user:manage} 权限）能成功调用「新建管理账号」「新建角色」接口，
 * 返回 {@code code=0}。也就是说**任何能登录的人都能给自己提权**。
 * 这类失效是静默的（没有异常、没有日志、接口文档上也看不出来），
 * 所以这里连同下面 {@code PermissionCatalog} 的键校验一起，作为必须守住的防线。
 *
 * <p>顺带说明：拦截器只负责「校验注解」，真正的权限数据来自
 * {@link SaTokenPermissionProvider}（每次查库），所以改权限立即生效。
 *
 * <p><b>不配置任何 CORS</b>：前端开发态走 Vite 代理、生产态走 Nginx 反代，
 * 都是同源请求。若图省事开 {@code allowedOrigins("*")} + {@code allowCredentials(true)}，
 * 等于给任意站点开了带凭证的跨站调用 —— 对管理交易系统的后台是高危配置。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentAdminUserArgumentResolver currentAdminUserArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAdminUserArgumentResolver);
    }

    /**
     * 注册鉴权拦截器。
     *
     * <p>{@code addPathPatterns("/**")} 拦全部路径（含登录接口）——
     * 登录接口本身只是没加校验注解，拦截器遇到无注解的方法会直接放行，
     * 比「按路径排除、日后新增接口忘记加进白名单」安全得多。
     * 换句话说：这里的策略是**默认拦截全部，靠注解放行**，
     * 而不是「默认放行，靠白名单拦截」。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter.match("/**")
                        .notMatch(
                                // 这三个是登录前就要能调的接口；它们各自没有鉴权注解，
                                // 这里列出来只是把「允许匿名」这件事写在明面上，
                                // 便于日后审查「哪些接口是公开的」。
                                "/system/auth/login",
                                "/system/auth/logout"
                        )
                        .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }
}
