package com.github.reactivity512.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.github.reactivity512.rag.embedding.EmbeddingProperties;
import com.github.reactivity512.rag.embedding.OllamaEmbeddingModel;

/**
 * Клиент Ollama для эмбеддингов.
 *
 * <p>Собственный {@link OllamaEmbeddingModel} регистрируется как бин
 * {@code EmbeddingModel}: этого достаточно, чтобы им пользовались и индексация,
 * и поиск, без привязки к авто-конфигурации Spring AI.
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfig {

    @Bean
    public RestClient ollamaRestClient(EmbeddingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofMillis(properties.getTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    public OllamaEmbeddingModel embeddingModel(RestClient ollamaRestClient, EmbeddingProperties properties) {
        return new OllamaEmbeddingModel(ollamaRestClient, properties);
    }
}
