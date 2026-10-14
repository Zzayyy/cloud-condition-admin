package com.qianlong.cloud.admin.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Redis 实例清单配置（{@code app.redis.*}）。
 *
 * <p><b>为什么是「列表」而不是单个 host：</b>现场 acc-* 键分散在多个实例的多个 db 上
 * （实测：136 db0 有 1 个、136 db1 有 12 个、137 db1 有 1 个）。
 * Spring Boot 的 {@code spring.data.redis.*} 只能表达一个连接，
 * 所以本模块不用它的自动配置，改为按这份清单自建客户端（见 RedisAccountRepository）。
 *
 * <p>账号页要展示「Redis 侧实时态」，少读一个库就会表现为
 * 「某些账号 hasRedis=false」——那不是「没有」，而是「没查到」，两者不能混。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.redis")
public class RedisProperties {

    /** 单次 SCAN 的 COUNT 批次大小 */
    private int scanCount = 500;

    /** 命令超时（毫秒）：Redis 不可达时要尽快失败，别把接口挂住 */
    private long timeoutMs = 3000;

    private List<Instance> instances = new ArrayList<>();

    /** 一个 Redis 实例（含具体 db） */
    @Data
    public static class Instance {
        /** 稳定标识，前端 redisAccount 详情里会用到 */
        private String id;
        private String host;
        private int port = 6379;
        private int db = 0;
        private String password;

        /** 展示用，如 192.168.1.136/db0 */
        public String display() {
            return host + "/db" + db;
        }
    }
}
