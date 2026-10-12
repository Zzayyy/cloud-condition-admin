package com.qianlong.cloud.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 云条件单后台管理系统 —— 后端入口。
 *
 * <p>当前实现范围：系统管理模块（管理用户与权限 / 角色与权限 / 操作审计 + 登录鉴权）。
 *
 * <p>重要约定（与前端 {@code src/api/} 的契约一致）：
 * <ul>
 *   <li>上下文路径 {@code /api}，与前端 Vite 代理的 {@code VITE_API_BASE_URL=/api} 对齐；</li>
 *   <li>所有接口返回 HTTP 200，业务结果放在 {@code {code,message,data}} 信封里；</li>
 *   <li>时间一律序列化为 {@code yyyy-MM-dd HH:mm:ss}（前端按字符串比较时间范围）。</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("com.qianlong.cloud.admin.mapper")
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
