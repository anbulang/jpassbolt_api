package com.jpassbolt.api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 全局 Jackson 定制：把 {@link LocalDateTime} 序列化为带 UTC offset 的 RFC3339 字符串
 * （形如 {@code 2012-07-04T13:39:25+00:00}），以满足 OpenAPI 中 42 处
 * {@code format: date-time} 字段的校验（Atlassian swagger-request-validator 按 RFC3339 要求 offset）。
 *
 * <p>策略（见日期审计推荐方案）：实体/DTO 字段类型仍保持 {@link LocalDateTime} 不变，
 * 仅在序列化层把值视为 UTC（库内写入侧已统一改为 {@code LocalDateTime.now(ZoneOffset.UTC)}，
 * 故这里 {@code atOffset(UTC)} 不会产生时刻偏移）后输出 offset。覆盖所有 DTO 以及手工
 * 构造的 Map 信封中的 LocalDateTime 字段，无需逐 DTO 加 {@code @JsonFormat}。</p>
 *
 * <p>反序列化侧容忍带/不带 offset 的入站值，保持与 {@code UserService.parseDateTime}
 * （ISO_DATE_TIME）等入站解析兼容。</p>
 *
 * <p>注意：信封 header 的 servertime 是 epoch 秒整数（不是 date-time 字符串），不受此定制影响；
 * 不使用 {@code spring.jackson.date-format}，那只作用于 java.util.Date 而非 java.time 类型。</p>
 */
@Configuration
public class JacksonConfig {

    /** RFC3339（带 offset），如 2012-07-04T13:39:25+00:00。 */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeRfc3339Customizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeUtcSerializer());
            builder.deserializerByType(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
        };
    }

    /**
     * 把 LocalDateTime 视为 UTC 时刻，输出带 +00:00 offset 的 RFC3339 字符串。
     */
    static final class LocalDateTimeUtcSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.atOffset(ZoneOffset.UTC).format(RFC3339));
        }
    }

    /**
     * 容忍带 offset（RFC3339）或不带 offset（裸 ISO local）的入站值，统一归一到 UTC 的 LocalDateTime。
     */
    static final class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getValueAsString();
            if (text == null || text.isEmpty()) {
                return null;
            }
            try {
                // 带 offset：先解析为 OffsetDateTime，再归一到 UTC 的 LocalDateTime。
                return OffsetDateTime.parse(text, RFC3339)
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                // 不带 offset：按裸 ISO local 解析。
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME);
            }
        }
    }
}
