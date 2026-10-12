package com.github.reactivity512.rag.api;

import org.springframework.http.HttpStatus;

/**
 * Ошибка API с HTTP-статусом.
 *
 * <p>Тексты сообщений уходят клиенту в поле {@code detail} — формат сохранён
 * от Python-версии, фронтенд читает именно его.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
