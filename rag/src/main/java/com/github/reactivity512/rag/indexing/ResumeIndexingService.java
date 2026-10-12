package com.github.reactivity512.rag.indexing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.ai.embedding.EmbeddingModel;
import com.github.reactivity512.rag.ingest.ResumeFormatException;
import com.github.reactivity512.rag.ingest.SourceFormat;
import com.github.reactivity512.rag.ingest.domain.ParsedResume;
import com.github.reactivity512.rag.ingest.extract.FormatDetector;
import com.github.reactivity512.rag.ingest.extract.ResumeTextExtractor;
import com.github.reactivity512.rag.ingest.parse.ResumeParser;
import com.github.reactivity512.rag.vector.QdrantCollectionService;
import com.github.reactivity512.rag.vector.ResumeVectorPoint;

/**
 * Оркестрация индексации одного резюме:
 * определение формата → извлечение текста → разбор → эмбеддинг → upsert.
 *
 * <p>Идентификатор точки детерминированный: UUID от sha256 нормализованного
 * текста. Повторная загрузка того же документа перезаписывает точку, а не
 * создаёт дубль, а одинаковые резюме в разных форматах дают один id.
 */
@Service
public class ResumeIndexingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeIndexingService.class);

    /** Сколько точек отправлять в Qdrant за один вызов. */
    private static final int UPSERT_BATCH_SIZE = 32;

    private final ResumeTextExtractor textExtractor;
    private final ResumeParser parser;
    private final EmbeddingModel embeddingModel;
    private final QdrantCollectionService qdrant;
    private final ResumePayloadBuilder payloadBuilder;

    public ResumeIndexingService(
            ResumeTextExtractor textExtractor,
            ResumeParser parser,
            EmbeddingModel embeddingModel,
            QdrantCollectionService qdrant,
            ResumePayloadBuilder payloadBuilder) {
        this.textExtractor = textExtractor;
        this.parser = parser;
        this.embeddingModel = embeddingModel;
        this.qdrant = qdrant;
        this.payloadBuilder = payloadBuilder;
    }

    /**
     * Индексирует содержимое файла.
     *
     * @param content          байты файла
     * @param fileName         имя файла (для payload и сообщений об ошибке)
     * @param originalFileName имя загруженного файла, если оно отличалось
     * @param uploadedAt       время загрузки (сохраняется из статуса задачи)
     * @return результат с id точки и разобранными полями
     */
    public IndexingResult index(byte[] content, String fileName, String originalFileName, String uploadedAt) {
        SourceFormat format = FormatDetector.detect(content);
        if (format == null) {
            throw new ResumeFormatException("Формат файла не поддерживается: " + fileName
                    + ". Допустимы txt, pdf, docx и rtf.");
        }

        String text = textExtractor.extract(content, fileName, format);
        ParsedResume resume = parser.parse(text, fileName);

        String contentHash = sha256(resume.fullText());
        String resumeId = resumeIdFromHash(contentHash);
        String indexedAt = Instant.now().toString();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(PayloadKeys.FILE_NAME, fileName);
        metadata.put(PayloadKeys.ORIGINAL_FILE_NAME, originalFileName == null ? fileName : originalFileName);
        metadata.put(PayloadKeys.SOURCE_FORMAT, format.extension().substring(1));
        metadata.put(PayloadKeys.CONTENT_HASH, contentHash);
        metadata.put(PayloadKeys.UPLOADED_AT, uploadedAt == null ? indexedAt : uploadedAt);
        metadata.put(PayloadKeys.INDEXED_AT, indexedAt);

        Map<String, Object> payloadMap = payloadBuilder.build(resume, metadata);

        float[] vector = embeddingModel.embed(resume.fullText());
        upsert(List.of(new ResumeVectorPoint(resumeId, toFloatList(vector), payloadMap)));

        log.info("Резюме проиндексировано: id={} файл={} формат={} опыт={} навыков={}",
                resumeId, fileName, format, resume.experience().size(), resume.skills().size());
        return new IndexingResult(resumeId, contentHash, resume, payloadMap);
    }

    /** Батчевая загрузка точек в Qdrant. */
    public void upsert(List<ResumeVectorPoint> points) {
        for (int from = 0; from < points.size(); from += UPSERT_BATCH_SIZE) {
            int to = Math.min(from + UPSERT_BATCH_SIZE, points.size());
            qdrant.upsert(points.subList(from, to));
        }
    }

    /** sha256 от текста — идентификатор содержимого. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в этой JVM", e);
        }
    }

    /**
     * Детерминированный id точки: Qdrant принимает строку только в формате UUID,
     * поэтому детерминированный UUID строится из хэша содержимого.
     */
    public static String resumeIdFromHash(String contentHash) {
        return UUID.nameUUIDFromBytes(contentHash.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    /** Результат индексации одного резюме. */
    public record IndexingResult(
            String resumeId,
            String contentHash,
            ParsedResume resume,
            Map<String, Object> payload) {
    }
}
