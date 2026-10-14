package com.qianlong.cloud.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 云条件单后台管理系统 —— 后端入口。
 *
 * <p>当前实现范围：
 * <ul>
 *   <li>登录鉴权（登录 / 登出 / 资料 / 改密）</li>
 *   <li>系统管理：管理用户与权限 / 角色与权限 / 操作审计</li>
 *   <li>账户与用户：用户账号 / 签署与股东号 / 登录日志</li>
 *   <li>业务数据：条件单管理（只读）</li>
 * </ul>
 *
 * <p>重要约定（与前端 {@code src/api/} 的契约一致）：
 * <ul>
 *   <li>上下文路径 {@code /api}，与前端 Vite 代理的 {@code VITE_API_BASE_URL=/api} 对齐；</li>
 *   <li>所有接口返回 HTTP 200，业务结果放在 {@code {code,message,data}} 信封里；</li>
 *   <li>时间一律序列化为 {@code yyyy-MM-dd HH:mm:ss}（前端按字符串比较时间范围）。</li>
 * </ul>
 *
 * <p>{@code @MapperScan} 覆盖 {@code mapper} 包下**所有**接口（不限于
 * MyBatis-Plus 的 {@code BaseMapper} 子接口），所以像
 * {@code ConditionalMapper} 这种纯注解 SQL 的接口也会被注册。
 */
@SpringBootApplication
@MapperScan("com.qianlong.cloud.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
