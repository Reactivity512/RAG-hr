package com.github.reactivity512.rag.vector;

import java.util.List;
import java.util.Map;

/**
 * Результат гибридного поиска: payload и итоговый скор RRF.
 */
public record SearchHit(Map<String, Object> payload, double score) {

    public String string(String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
