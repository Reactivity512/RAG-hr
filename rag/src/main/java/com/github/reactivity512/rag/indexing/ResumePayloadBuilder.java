package com.github.reactivity512.rag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.reactivity512.rag.ingest.domain.EducationEntry;
import com.github.reactivity512.rag.ingest.domain.ExperienceEntry;
import com.github.reactivity512.rag.ingest.domain.ParsedResume;

/**
 * Сборка payload точки Qdrant.
 *
 * <p>Структурированно хранятся только рабочие поля — пол, должность, опыт,
 * образование, навыки. Остальное резюме лежит в {@code full_text} и участвует
 * в эмбеддинге и в BM25.
 *
 * <p>Карта собирается явно, а не сериализацией записи: в Jackson 3 нет
 * прежнего {@code @JsonProperty}, поэтому только так можно гарантировать, что
 * ключи совпадают с {@link PayloadKeys}, которые читают API и поиск.
 * {@code content_hash} — sha256 от {@code full_text}; по нему строится
 * детерминированный id точки, что даёт идемпотентную переиндексацию и задел
 * под дедупликацию.
 */
@Component
public class ResumePayloadBuilder {

    /** Собирает payload из распарсенного резюме и метаданных файла. */
    public Map<String, Object> build(ParsedResume resume, Map<String, Object> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PayloadKeys.FILE_NAME, metadata.get(PayloadKeys.FILE_NAME));
        payload.put(PayloadKeys.ORIGINAL_FILE_NAME, metadata.get(PayloadKeys.ORIGINAL_FILE_NAME));
        payload.put(PayloadKeys.SOURCE_FORMAT, metadata.get(PayloadKeys.SOURCE_FORMAT));
        payload.put(PayloadKeys.CONTENT_HASH, metadata.get(PayloadKeys.CONTENT_HASH));
        payload.put(PayloadKeys.UPLOADED_AT, metadata.get(PayloadKeys.UPLOADED_AT));
        payload.put(PayloadKeys.INDEXED_AT, metadata.get(PayloadKeys.INDEXED_AT));

        payload.put(PayloadKeys.NAME, resume.name());
        payload.put(PayloadKeys.GENDER, resume.gender());
        payload.put(PayloadKeys.TITLE, resume.title());
        payload.put(PayloadKeys.FULL_TEXT, resume.fullText());
        payload.put(PayloadKeys.SKILLS, List.copyOf(resume.skills()));
        payload.put(PayloadKeys.EXPERIENCE, experiencePayload(resume.experience()));
        payload.put(PayloadKeys.EDUCATION, educationPayload(resume.education()));
        return payload;
    }

    private List<Map<String, Object>> experiencePayload(List<ExperienceEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>(entries.size());
        for (ExperienceEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("period", entry.period());
            item.put("company", entry.company());
            item.put("city", entry.city());
            item.put("position", entry.position());
            item.put("description", entry.description());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> educationPayload(List<EducationEntry> entries) {
        List<Map<String, Object>> result = new ArrayList<>(entries.size());
        for (EducationEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("year", entry.year());
            item.put("institution", entry.institution());
            item.put("specialty", entry.specialty());
            result.add(item);
        }
        return result;
    }
}
