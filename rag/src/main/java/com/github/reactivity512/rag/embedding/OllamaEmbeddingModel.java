package com.github.reactivity512.rag.embedding;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.web.client.RestClient;

/**
 * Модель эмбеддингов поверх Ollama {@code /api/embed}.
 *
 * <p>Реализована своим кодом, а не через Spring AI-стартер, по двум причинам:
 * <ul>
 *   <li>dimension известен заранее (1024) — его надо отдавать в Qdrant и
 *       проверять на старте, а не вычислять лениво;
 *   <li>нужен контроль над размером батча и внятная ошибка при недоступности
 *       Ollama, потому что входные резюме длинные.
 * </ul>
 *
 * <p>Входные тексты не обрезаются здесь: лимит контекста задаётся на стороне
 * Ollama ({@code OLLAMA_CONTEXT_LENGTH}); Ollama молча усекает слишком длинный
 * вход, поэтому проверка длины выполняется вызывающим кодом до эмбеддинга.
 */
public class OllamaEmbeddingModel extends AbstractEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingModel.class);

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    public OllamaEmbeddingModel(RestClient ollamaRestClient, EmbeddingProperties properties) {
        this.restClient = ollamaRestClient;
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** Эмбеддинг одного документа (контракт Spring AI). */
    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<float[]> vectors = embedAll(instructions);
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(
                properties.getModel(), null);
        return new EmbeddingResponse(embeddings, metadata);
    }

    /** Эмбеддинги для списка текстов; батчи отправляются последовательно. */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            result.addAll(embedBatch(texts.subList(from, to)));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> batch) {
        OllamaEmbedDtos.EmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/api/embed")
                    .body(new OllamaEmbedDtos.EmbedRequest(properties.getModel(), batch))
                    .retrieve()
                    .body(OllamaEmbedDtos.EmbedResponse.class);
        } catch (RuntimeException e) {
            throw new EmbeddingUnavailableException(
                    "Ollama недоступна или модель '" + properties.getModel()
                            + "' не загружена. Проверьте сервис Ollama. Ошибка: " + e.getMessage(), e);
        }
        if (response == null || response.isEmpty()) {
            throw new EmbeddingUnavailableException(
                    "Ollama не вернула эмбеддинги для модели '" + properties.getModel() + "'");
        }

        List<float[]> vectors = new ArrayList<>(response.embeddings().size());
        for (List<Double> raw : response.embeddings()) {
            float[] vector = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                vector[i] = raw.get(i).floatValue();
            }
            vectors.add(vector);
        }
        if (vectors.size() != batch.size()) {
            throw new EmbeddingUnavailableException(String.format(
                    "Ollama вернула %d векторов на %d текстов", vectors.size(), batch.size()));
        }
        int actualDimensions = vectors.getFirst().length;
        if (actualDimensions != properties.getDimensions()) {
            throw new EmbeddingUnavailableException(String.format(
                    "Модель '%s' вернула вектор размерности %d, а ожидается %d. "
                            + "Размерность коллекции Qdrant не совпадёт с эмбеддингами.",
                    properties.getModel(), actualDimensions, properties.getDimensions()));
        }
        log.debug("Эмбеддинги получены: {} шт., размерность {}", vectors.size(), actualDimensions);
        return vectors;
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }
}
