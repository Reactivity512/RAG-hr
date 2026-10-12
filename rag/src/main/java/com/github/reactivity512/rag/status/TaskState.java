package com.github.reactivity512.rag.status;

import java.util.Locale;

/**
 * Статус обработки резюме.
 *
 * <p>Значения совпадают с Python-версией, чтобы фронтенд (бейджи и подписи)
 * не пришлось менять. Дополнительно добавлен {@link #RETRYING}: задача
 * упала по временной причине и будет повторена.
 */
public enum TaskState {

    QUEUED,
    PROCESSING,
    RETRYING,
    INDEXED,
    FAILED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TaskState fromWire(String value) {
        if (value == null) {
            return null;
        }
        return TaskState.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
