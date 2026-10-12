package com.github.reactivity512.rag.embedding;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Тела запросов и ответов Ollama Embeddings API.
 */
public final class OllamaEmbedDtos {

    private OllamaEmbedDtos() {
    }

    /** Запрос {@code POST /api/embed}: один или несколько текстов. */
    public record EmbedRequest(String model, List<String> input) {
    }

    /** Ответ {@code POST /api/embed}. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EmbedResponse(List<List<Double>> embeddings, Long promptEvalCount) {

        public boolean isEmpty() {
            return embeddings == null || embeddings.isEmpty();
        }
    }
}
