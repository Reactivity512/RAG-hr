package com.github.reactivity512.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.reactivity512.rag.vector.QdrantProperties;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

/**
 * Подключение к Qdrant по gRPC.
 *
 * <p>Порты: 6333 — REST, 6334 — gRPC. Java-клиент работает по gRPC,
 * поэтому по умолчанию используется 6334.
 */
@Configuration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                properties.getHost(), properties.getPort(), properties.isTls());
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.withApiKey(properties.getApiKey());
        }
        return new QdrantClient(builder.build());
    }
}
