package com.github.reactivity512.rag.config;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.github.reactivity512.rag.embedding.OllamaEmbeddingModel;
import com.github.reactivity512.rag.vector.QdrantCollectionService;

/**
 * Fail-fast на старте: приложение должно падать с понятной ошибкой, если
 * инфраструктура недоступна, а не работать «молча вхолостую».
 *
 * <p>Проверяются Redis (нужен и API, и воркеру) и Qdrant. Модель эмбеддингов
 * проверяется отдельно: если Ollama ещё поднимается, приложение не падает,
 * а пишет предупреждение — при этом первая же задача честно уйдёт в retry.
 */
@Configuration
public class StartupChecksConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupChecksConfig.class);

    @Bean
    public ApplicationRunner infrastructureHealthCheck(
            RedisConnectionFactory redisConnectionFactory,
            QdrantCollectionService qdrant,
            OllamaEmbeddingModel embeddingModel,
            org.springframework.core.env.Environment environment) {
        return args -> {
            requireRedis(redisConnectionFactory);
            requireQdrant(qdrant);
            checkEmbeddingModel(embeddingModel, environment);
            log.info("Старт завершён: Redis и Qdrant доступны, коллекция '{}'",
                    qdrant.collectionName());
        };
    }

    private void requireRedis(RedisConnectionFactory connectionFactory) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis ответил неожиданно: " + pong);
            }
            log.info("Redis доступен");
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Redis недоступен — приложение не может стартовать: " + e.getMessage(), e);
        }
    }

    private void requireQdrant(QdrantCollectionService qdrant) {
        boolean created = qdrant.ensureCollection();
        if (created) {
            log.info("Коллекция '{}' создана заново", qdrant.collectionName());
        } else {
            log.info("Коллекция '{}' уже существует и совпадает по схеме", qdrant.collectionName());
        }
    }

    /**
     * Проверяет размерность эмбеддингов. Если Ollama недоступна, старт не
     * прерывается: сообщение попадёт в лог, а задача уйдёт в retry.
     */
    private void checkEmbeddingModel(OllamaEmbeddingModel embeddingModel,
            org.springframework.core.env.Environment environment) {
        boolean strict = environment.getProperty(
                "rag.embedding.fail-fast", Boolean.class, Boolean.FALSE);
        try {
            float[] probe = embeddingModel.embed("проверка размерности эмбеддингов");
            log.info("Модель эмбеддингов готова: размерность {}", probe.length);
        } catch (RuntimeException e) {
            String message = "Модель эмбеддингов недоступна: " + e.getMessage();
            if (strict) {
                throw new IllegalStateException(message, e);
            }
            log.warn("{} (старт продолжается, задачи будут повторяться)", message);
        }
    }

    /** Утилита для повторных проверок доступности. */
    static <T> T withRetry(Supplier<T> action, int attempts, long delayMs) {
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Проверка прервана", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("Проверка не удалась") : last;
    }
}
