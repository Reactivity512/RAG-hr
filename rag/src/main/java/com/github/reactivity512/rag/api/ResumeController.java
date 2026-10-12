package com.github.reactivity512.rag.api;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.github.reactivity512.rag.api.dto.ApiDtos;
import com.github.reactivity512.rag.indexing.PayloadKeys;
import com.github.reactivity512.rag.queue.IndexingJobPublisher;
import com.github.reactivity512.rag.status.TaskStatus;
import com.github.reactivity512.rag.status.TaskStatusRepository;
import com.github.reactivity512.rag.storage.ResumeFileStorage;

import tools.jackson.databind.ObjectMapper;

/**
 * Управление резюме:
 * upload → 202, список, карточка, скачивание оригинала, удаление.
 *
 * <p>Валидация «мягкая» только в части разбора: обязательные разделы
 * (должность, опыт, навыки) проверяются на этапе загрузки, чтобы не ставить
 * в очередь заведомо непригодный файл.
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeFileStorage storage;
    private final ResumeQueryService queryService;
    private final TaskStatusRepository statusRepository;
    private final IndexingJobPublisher jobPublisher;
    private final ObjectMapper payloadMapper;

    public ResumeController(
            ResumeFileStorage storage,
            ResumeQueryService queryService,
            TaskStatusRepository statusRepository,
            IndexingJobPublisher jobPublisher,
            ObjectMapper resumePayloadObjectMapper) {
        this.storage = storage;
        this.queryService = queryService;
        this.statusRepository = statusRepository;
        this.jobPublisher = jobPublisher;
        this.payloadMapper = resumePayloadObjectMapper;
    }

    /** Загрузка файла: файл сохраняется, задача ставится в очередь. */
    @PostMapping("/upload")
    public ResponseEntity<ApiDtos.UploadResponse> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty() || file.getOriginalFilename() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Не указано имя файла");
        }
        byte[] content = file.getBytes();
        if (content.length > storage.maxUploadBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Файл слишком большой: " + content.length + " байт (лимит "
                            + storage.maxUploadBytes() + ")");
        }
        String originalName = file.getOriginalFilename();
        // Текстовые файлы проверяем на UTF-8 сразу: битые байты отсекаем до очереди
        if (originalName.toLowerCase().endsWith(".txt")) {
            requireUtf8(content, originalName);
        }

        String savedName = storage.save(originalName, content);
        String taskId = UUID.randomUUID().toString();
        statusRepository.createQueued(taskId, savedName);

        try {
            jobPublisher.enqueue(taskId, savedName);
        } catch (RuntimeException e) {
            statusRepository.delete(taskId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось поставить задачу в очередь: " + e.getMessage());
        }

        log.info("Резюме принято: {} -> задача {}", savedName, taskId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiDtos.UploadResponse(taskId, savedName, "queued"));
    }

    /** Список готовых резюме. */
    @GetMapping
    public ApiDtos.ResumeListResponse list(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        List<ApiDtos.ResumeListItem> items = new ArrayList<>();
        for (ResumeQueryService.ResumeRecord record : queryService.list(limit)) {
            items.add(new ApiDtos.ResumeListItem(
                    record.id(),
                    record.string(PayloadKeys.FILE_NAME),
                    record.string(PayloadKeys.NAME),
                    record.string(PayloadKeys.TITLE),
                    null,
                    null,
                    record.stringList(PayloadKeys.SKILLS),
                    record.string(PayloadKeys.SOURCE_FORMAT),
                    record.string(PayloadKeys.UPLOADED_AT),
                    record.string(PayloadKeys.INDEXED_AT)));
        }
        return new ApiDtos.ResumeListResponse(items, items.size());
    }

    /** Полная карточка резюме. */
    @GetMapping("/{resumeId}")
    public ApiDtos.ResumeDetail get(@PathVariable String resumeId) {
        ResumeQueryService.ResumeRecord record = queryService.get(resumeId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Резюме " + resumeId + " не найдено");
        }
        Map<String, Object> payload = record.payload();
        return new ApiDtos.ResumeDetail(
                record.id(),
                record.string(PayloadKeys.FILE_NAME),
                record.string(PayloadKeys.ORIGINAL_FILE_NAME),
                record.string(PayloadKeys.SOURCE_FORMAT),
                record.string(PayloadKeys.NAME),
                record.string(PayloadKeys.GENDER),
                record.string(PayloadKeys.TITLE),
                convertList(payload.get(PayloadKeys.EXPERIENCE), ApiDtos.ExperienceItem.class),
                convertList(payload.get(PayloadKeys.EDUCATION), ApiDtos.EducationItem.class),
                record.stringList(PayloadKeys.SKILLS),
                record.string(PayloadKeys.FULL_TEXT),
                record.string(PayloadKeys.UPLOADED_AT),
                record.string(PayloadKeys.INDEXED_AT));
    }

    /** Преобразует список словарей payload в список DTO. */
    private <T> List<T> convertList(Object raw, Class<T> type) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<T> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(payloadMapper.convertValue(item, type));
        }
        return result;
    }

    /** Скачивание оригинала резюме. */
    @GetMapping("/{resumeId}/file")
    public ResponseEntity<byte[]> download(@PathVariable String resumeId) {
        ResumeQueryService.ResumeRecord record = queryService.get(resumeId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Резюме " + resumeId + " не найдено");
        }
        String fileName = record.string(PayloadKeys.ORIGINAL_FILE_NAME);
        if (fileName == null) {
            fileName = record.string(PayloadKeys.FILE_NAME);
        }
        byte[] content = storage.read(record.string(PayloadKeys.FILE_NAME));
        MediaType contentType = resolveContentType(fileName);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(content);
    }

    /** Удаление резюме: точка в Qdrant, оригинал на диске и статус задачи. */
    @DeleteMapping("/{resumeId}")
    public ResponseEntity<Void> delete(@PathVariable String resumeId) {
        ResumeQueryService.ResumeRecord record = queryService.get(resumeId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Резюме " + resumeId + " не найдено");
        }
        String fileName = record.string(PayloadKeys.FILE_NAME);
        queryService.delete(resumeId);
        if (fileName != null) {
            storage.delete(fileName);
        }
        for (TaskStatus task : statusRepository.list(null)) {
            if (resumeId.equals(task.resumeId())) {
                statusRepository.delete(task.taskId());
                break;
            }
        }
        return ResponseEntity.noContent().build();
    }

    private MediaType resolveContentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".docx")) {
            return MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (lower.endsWith(".rtf")) {
            return MediaType.parseMediaType("application/rtf");
        }
        return new MediaType("text", "plain", StandardCharsets.UTF_8);
    }

    private void requireUtf8(byte[] content, String fileName) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
        } catch (CharacterCodingException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Файл " + fileName + " должен быть в кодировке UTF-8");
        }
    }
}
