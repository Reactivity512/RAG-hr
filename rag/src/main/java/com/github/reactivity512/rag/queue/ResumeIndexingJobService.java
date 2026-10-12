package com.github.reactivity512.rag.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.reactivity512.rag.indexing.ResumeIndexingService;
import com.github.reactivity512.rag.ingest.ResumeFormatException;
import com.github.reactivity512.rag.status.TaskState;
import com.github.reactivity512.rag.status.TaskStatusRepository;
import com.github.reactivity512.rag.storage.ResumeFileStorage;

/**
 * Обработка одной задачи индексации резюме.
 *
 * <p>Поток статусов в Redis: {@code QUEUED} (ставит API) → {@code PROCESSING} →
 * {@code INDEXED} или {@code FAILED}. Терминальные ошибки (формат файла,
 * отсутствие обязательных разделов) не повторяются, временные (Ollama или
 * Qdrant недоступны) — повторяются, это решает консьюмер.
 */
@Service
public class ResumeIndexingJobService {

    private static final Logger log = LoggerFactory.getLogger(ResumeIndexingJobService.class);

    private final ResumeFileStorage storage;
    private final ResumeIndexingService indexingService;
    private final TaskStatusRepository statusRepository;

    public ResumeIndexingJobService(
            ResumeFileStorage storage,
            ResumeIndexingService indexingService,
            TaskStatusRepository statusRepository) {
        this.storage = storage;
        this.indexingService = indexingService;
        this.statusRepository = statusRepository;
    }

    /**
     * Индексирует резюме по имени файла из хранилища.
     *
     * @return id точки в Qdrant
     */
    public String process(String taskId, String fileName) {
        statusRepository.update(taskId, fileName, TaskState.PROCESSING, null, null);
        try {
            byte[] content = storage.read(fileName);
            ResumeIndexingService.IndexingResult result = indexingService.index(content, fileName, fileName, null);
            statusRepository.update(taskId, fileName, TaskState.INDEXED, result.resumeId(), null);
            return result.resumeId();
        } catch (ResumeFormatException e) {
            // Терминальная ошибка: повторять бессмысленно
            log.warn("Задача {}: файл {} не подходит: {}", taskId, fileName, e.getMessage());
            statusRepository.update(taskId, fileName, TaskState.FAILED, null, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            // Временная ошибка: задача будет повторена
            log.warn("Задача {}: сбой обработки файла {}: {}", taskId, fileName, e.getMessage());
            statusRepository.update(taskId, fileName, TaskState.RETRYING, null, e.getMessage());
            throw e;
        }
    }
}
