package com.github.reactivity512.rag.search;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.github.reactivity512.rag.embedding.OllamaEmbeddingModel;
import com.github.reactivity512.rag.indexing.PayloadKeys;
import com.github.reactivity512.rag.vector.QdrantCollectionService;

import io.qdrant.client.grpc.Points.ScoredPoint;

/**
 * RAG-поиск: гибридный retrieval в Qdrant + генерация ответа LLM.
 *
 * <p>Бин существует только в профиле {@code api}: воркеру чат не нужен, и
 * требовать от него {@code ChatModel} нельзя.
 *
 * <p>LLM-бэкенд выбирается настройками Spring AI: нативный Ollama или
 * OpenAI-совместимый клиент (vLLM) — оба варианта описаны в
 * {@code application-api.yml}.
 */
@Service
@Profile("api")
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final String SYSTEM_PROMPT = """
            Ты — опытный HR-ассистент и IT-рекрутер.
            Твоя задача — проанализировать предоставленные резюме и оценить, насколько каждый кандидат соответствует запросу пользователя.

            Раздели кандидатов на 4 группы по степени соответствия:

            🟢 Зелёный — полностью подходит: все ключевые требования выполнены
            🟡 Жёлтый — подходит с нюансами: основные требования выполнены, но есть мелкие пробелы
            🟠 Оранжевый — плохо подходит: часть важных требований не выполнена
            🔴 Красный — не подходит: не соответствует ключевым требованиям

            Для каждого кандидата укажи:
            - Имя/специальность (из резюме)
            - Почему он попал в эту группу (кратко, 1–2 предложения)
            - Ключевые навыки, релевантные запросу

            Группы, в которых нет кандидатов, пропускай. Если ни один кандидат не подошёл — честно скажи об этом.
            Не придумывай информацию, которой нет в тексте резюме.
            """;

    private final OllamaEmbeddingModel embeddingModel;
    private final QdrantCollectionService qdrant;
    private final ChatModel chatModel;

    /**
     * @param openAiChatModel чат через OpenAI-совместимый API: работает и с
     *                        Ollama ({@code /v1}), и с vLLM — как в Python-версии
     * @param ollamaChatModel нативный Ollama-чат; используется, если бэкенд
     *                        переключён на {@code ollama}
     * @param backend         какому бэкенду отдать предпочтение
     */
    public SearchService(
            OllamaEmbeddingModel embeddingModel,
            QdrantCollectionService qdrant,
            @Qualifier("openAiChatModel") ChatModel openAiChatModel,
            @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            @Value("${rag.llm.backend:openai}") String backend) {
        this.embeddingModel = embeddingModel;
        this.qdrant = qdrant;
        this.chatModel = "ollama".equalsIgnoreCase(backend) ? ollamaChatModel : openAiChatModel;
        log.info("LLM-бэкенд для поиска: {}", "ollama".equalsIgnoreCase(backend) ? "ollama" : "openai-compatible");
    }

    /** Поиск с генерацией ответа. */
    public SearchResult search(String query, int topK, List<String> requiredSkills) {
        float[] queryVector = embeddingModel.embed(query);
        List<Float> dense = new java.util.ArrayList<>(queryVector.length);
        for (float value : queryVector) {
            dense.add(value);
        }

        List<ScoredPoint> hits = qdrant.searchHybrid(query, dense, topK, requiredSkills);
        log.info("Гибридный поиск вернул {} резюме (top_k={}, фильтр навыков={})",
                hits.size(), topK, requiredSkills);

        if (hits.isEmpty()) {
            return new SearchResult("По вашему запросу не найдено подходящих резюме.", 0);
        }

        String context = buildContext(hits);
        String answer = generate(query, context);
        return new SearchResult(answer, hits.size());
    }

    /** Контекст для LLM: то, что нужно для оценки кандидата. */
    private String buildContext(List<ScoredPoint> hits) {
        StringBuilder sb = new StringBuilder(4096);
        int index = 1;
        for (ScoredPoint hit : hits) {
            Map<String, Object> payload = payloadOf(hit);
            sb.append("\n--- Резюме #").append(index++).append(" ---\n");
            sb.append("Файл: ").append(value(payload, PayloadKeys.FILE_NAME)).append('\n');
            sb.append("Имя: ").append(value(payload, PayloadKeys.NAME)).append('\n');
            sb.append("Должность: ").append(value(payload, PayloadKeys.TITLE)).append('\n');
            sb.append("Навыки: ").append(String.join(", ", stringList(payload, PayloadKeys.SKILLS))).append('\n');
            sb.append("Резюме:\n").append(value(payload, PayloadKeys.FULL_TEXT)).append('\n');
        }
        return sb.toString();
    }

    private String generate(String query, String context) {
        String userPrompt = "ЗАПРОС КАНДИДАТА:\n" + query + "\n\nНАЙДЕННЫЕ РЕЗЮМЕ:\n" + context;
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userPrompt))));
            String answer = response.getResult().getOutput().getText();
            return answer == null ? "" : answer;
        } catch (RuntimeException e) {
            // LLM недоступна — отдаём понятную ошибку, как в Python-версии
            log.warn("Обращение к LLM не удалось: {}", e.getMessage());
            return "Ошибка при обращении к LLM. Убедитесь, что backend запущен и модель доступна. "
                    + "Ошибка: " + e.getMessage();
        }
    }

    private Map<String, Object> payloadOf(ScoredPoint hit) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        hit.getPayload().forEach((key, value) ->
                payload.put(key, QdrantCollectionService.fromValue(value)));
        return payload;
    }

    private String value(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? "N/A" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }

    /** Ответ RAG-поиска. */
    public record SearchResult(String answer, int foundResumesCount) {
    }
}
