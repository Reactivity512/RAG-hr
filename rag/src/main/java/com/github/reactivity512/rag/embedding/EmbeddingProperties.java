package com.github.reactivity512.rag.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры эмбеддингов (Ollama, модель bge-m3).
 *
 * <p>Размерность подтверждена на живом сервисе: {@code POST /api/embed} для
 * {@code bge-m3} возвращает 1024 нормированных значения, что совпадает с
 * размерностью коллекции Qdrant.
 */
@ConfigurationProperties(prefix = "rag.embedding")
public class EmbeddingProperties {

    /** Базовый URL Ollama (без /v1). */
    private String baseUrl = "http://localhost:11434";

    /** Модель эмбеддингов. */
    private String model = "bge-m3";

    /** Сколько текстов отправлять в одном запросе. */
    private int batchSize = 8;

    /** Ожидаемая размерность вектора — проверяется на старте. */
    private int dimensions = 1024;

    /** Таймаут одного запроса, мс. */
    private long timeoutMs = 120_000;

    /**
     * Ограничение контекста модели на стороне Ollama.
     *
     * <p>Ollama молча обрезает вход по контексту, поэтому значение задаётся
     * переменной окружения {@code OLLAMA_CONTEXT_LENGTH} в docker-compose,
     * а здесь используется только для диагностики.
     */
    private int contextLength = 8192;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getContextLength() {
        return contextLength;
    }

    public void setContextLength(int contextLength) {
        this.contextLength = contextLength;
    }
}
