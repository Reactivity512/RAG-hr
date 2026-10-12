package com.github.reactivity512.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * ObjectMapper'ы для внутренних структур.
 *
 * <p>Web-контракт настраивается аннотациями на DTO, поэтому здесь отдельные
 * экземпляры: их вид не должен зависеть от HTTP-настроек.
 */
@Configuration
public class SerializationConfig {

    /** Для статусов задач в Redis. */
    @Bean
    public ObjectMapper taskStatusObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Для payload точки Qdrant.
     *
     * <p>Первые два поля ({@code file_name}, {@code full_text}) заданы явно,
     * поля с русскими именами остаются как есть — стратегия snake_case их не
     * трогает, а вложенные записи (опыт, образование) получают английские имена.
     */
    @Bean
    public ObjectMapper resumePayloadObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }
}
