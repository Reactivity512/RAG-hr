package com.github.reactivity512.rag.ingest;

/**
 * Файл резюме не может быть обработан: неподдерживаемый формат,
 * битый файл или документ не похож на резюме.
 *
 * <p>Терминальная ошибка: ретраить бессмысленно, задача помечается failed.
 */
public class ResumeFormatException extends RuntimeException {

    public ResumeFormatException(String message) {
        super(message);
    }

    public ResumeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
