package com.github.reactivity512.rag.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.github.reactivity512.rag.vector.QdrantCollectionService;

import io.qdrant.client.grpc.Points.RetrievedPoint;

/**
 * Чтение резюме из Qdrant для API.
 *
 * <p>Payload приводится к обычным Java-типам, а не отдаётся protobuf-структурой:
 * контроллеры и тесты работают с map.
 */
@Service
public class ResumeQueryService {

    private final QdrantCollectionService qdrant;

    public ResumeQueryService(QdrantCollectionService qdrant) {
        this.qdrant = qdrant;
    }

    /** Список резюме (короткие карточки). */
    public List<ResumeRecord> list(int limit) {
        List<ResumeRecord> records = new ArrayList<>();
        for (RetrievedPoint point : qdrant.scroll(limit)) {
            records.add(new ResumeRecord(point.getId().getUuid(), QdrantCollectionService.payloadOf(point)));
        }
        return records;
    }

    /** Одно резюме по id; null, если не найдено. */
    public ResumeRecord get(String resumeId) {
        Map<String, Object> payload = qdrant.retrievePayload(resumeId);
        if (payload == null) {
            return null;
        }
        return new ResumeRecord(resumeId, payload);
    }

    /** Удаляет резюме. */
    public void delete(String resumeId) {
        qdrant.delete(resumeId);
    }

    /** Резюме вместе с его payload. */
    public record ResumeRecord(String id, Map<String, Object> payload) {

        public String string(String key) {
            Object value = payload.get(key);
            return value == null ? null : String.valueOf(value);
        }

        @SuppressWarnings("unchecked")
        public List<String> stringList(String key) {
            Object value = payload.get(key);
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    result.add(String.valueOf(item));
                }
                return result;
            }
            return List.of();
        }
    }
}
