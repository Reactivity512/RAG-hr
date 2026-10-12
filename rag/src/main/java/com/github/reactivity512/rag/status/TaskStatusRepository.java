package com.github.reactivity512.rag.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

/**
 * Статусы обработки резюме.
 *
 * <p>Схема хранения сохранена от Python-версии: Redis-hash
 * {@code resumes:tasks}, поле — {@code task_id}, значение — JSON
 * {@link TaskStatus}. Это позволяет не менять фронтенд и не мигрировать
 * данные при переключении на Java.
 */
@Repository
public class TaskStatusRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusRepository.class);

    /** Ключ hash со статусами задач — как в Python-версии. */
    public static final String TASKS_HASH_KEY = "resumes:tasks";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public TaskStatusRepository(StringRedisTemplate redis, ObjectMapper taskStatusObjectMapper) {
        this.redis = redis;
        this.mapper = taskStatusObjectMapper;
    }

    /** Записывает или обновляет задачу. */
    public void save(TaskStatus task) {
        redis.opsForHash().put(TASKS_HASH_KEY, task.taskId(), mapper.writeValueAsString(task));
    }

    /** Одна задача по id; null, если нет. */
    public TaskStatus find(String taskId) {
        Object raw = redis.opsForHash().get(TASKS_HASH_KEY, taskId);
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readValue(raw.toString(), TaskStatus.class);
        } catch (RuntimeException e) {
            log.warn("Не удалось разобрать статус задачи {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Все задачи, свежие сверху.
     *
     * @param states null — без фильтра
     */
    public List<TaskStatus> list(List<TaskState> states) {
        Map<Object, Object> all = redis.opsForHash().entries(TASKS_HASH_KEY);
        List<TaskStatus> tasks = new ArrayList<>(all.size());
        for (Object raw : all.values()) {
            try {
                TaskStatus task = mapper.readValue(raw.toString(), TaskStatus.class);
                if (states == null || states.contains(task.status())) {
                    tasks.add(task);
                }
            } catch (RuntimeException e) {
                log.warn("Пропускаю некорректную запись задачи: {}", e.getMessage());
            }
        }
        tasks.sort(Comparator.comparing(TaskStatus::uploadedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return tasks;
    }

    /** Удаляет задачу. */
    public void delete(String taskId) {
        redis.opsForHash().delete(TASKS_HASH_KEY, taskId);
    }

    /** Создаёт запись в статусе {@code queued}. */
    public TaskStatus createQueued(String taskId, String filename) {
        TaskStatus task = new TaskStatus(
                taskId, filename, TaskState.QUEUED, Instant.now().toString(), null, null);
        save(task);
        return task;
    }

    /** Переводит задачу в новое состояние, сохраняя исходное время загрузки. */
    public TaskStatus update(String taskId, String filename, TaskState state, String resumeId, String error) {
        TaskStatus existing = find(taskId);
        String uploadedAt = existing != null && existing.uploadedAt() != null
                ? existing.uploadedAt()
                : Instant.now().toString();
        TaskStatus updated = new TaskStatus(taskId, filename, state, uploadedAt, resumeId, error);
        save(updated);
        return updated;
    }
}
