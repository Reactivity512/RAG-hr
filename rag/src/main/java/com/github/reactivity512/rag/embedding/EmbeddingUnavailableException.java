package com.github.reactivity512.rag.embedding;

/**
 * Модель эмбеддингов временно недоступна (сервис, модель или несовпадение
 * размерности). Такая ошибка считается временной: задачу имеет смысл повторить.
 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
