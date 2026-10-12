package com.qianlong.cloud.admin.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.qianlong.cloud.admin.common.DateTimes;

import java.time.LocalDateTime;

/**
 * JSON 序列化配置。
 *
 * <p>时间一律 {@code yyyy-MM-dd HH:mm:ss}（定宽零填充），与前端的时间筛选口径一致。
 * 若让 Jackson 用默认 ISO 输出（{@code 2026-10-15T10:00:00}），前端的字符串比较会
 * **静默筛错**（前端 README 第 8 条）。接口出入参都按这个格式走。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer dateTimeCustomizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(DateTimes.DATE_TIME))
                .deserializers(new LocalDateTimeDeserializer(DateTimes.DATE_TIME));
    }
}
