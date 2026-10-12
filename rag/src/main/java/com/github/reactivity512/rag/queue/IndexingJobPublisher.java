package com.github.reactivity512.rag.queue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Постановка задач индексации в Redis Stream.
 *
 * <p>API вызывает {@link #enqueue(String, String)} и сразу отвечает
 * {@code 202 Accepted}: парсинг и эмбеддинг выполняет воркер. Redis Stream
 * даёт at-least-once доставку через consumer group, а {@code XACK} после
 * обработки заменяет {@code task_acks_late} из Celery.
 */
@Component
@EnableConfigurationProperties(QueueProperties.class)
public class IndexingJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(IndexingJobPublisher.class);

    public static final String FIELD_TASK_ID = "taskId";
    public static final String FIELD_FILE_NAME = "fileName";
    public static final String FIELD_ATTEMPT = "attempt";
    public static final String FIELD_ERROR = "error";

    /** Служебное поле записи, которой создаётся пустой stream. */
    public static final String FIELD_BOOTSTRAP = "bootstrap";

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    public IndexingJobPublisher(StringRedisTemplate redis, QueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Создаёт stream и consumer group, если их ещё нет. */
    @PostConstruct
    public void ensureStreamAndGroup() {
        try {
            ensureStreamExists(properties.getStreamKey());
            createGroupIfMissing(properties.getStreamKey());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Не удалось подготовить очередь в Redis ('" + properties.getConsumerGroup()
                            + "'): " + e.getMessage(), e);
        }
    }

    /** Ставит задачу в очередь. */
    public String enqueue(String taskId, String fileName) {
        return add(taskId, fileName, 1, properties.getStreamKey());
    }

    /** Повторная постановка задачи после неудачной попытки. */
    public String retry(String taskId, String fileName, int attempt) {
        return add(taskId, fileName, attempt, properties.getStreamKey());
    }

    /** Задача, исчерпавшая попытки, уезжает в отдельный stream. */
    public String toDeadLetter(String taskId, String fileName, String error) {
        log.warn("Задача {} ({}) перемещена в {}: {}", taskId, fileName, properties.getDeadLetterKey(), error);
        String entryId = add(taskId, fileName, 0, properties.getDeadLetterKey());
        return entryId;
    }

    private String add(String taskId, String fileName, int attempt, String streamKey) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put(FIELD_TASK_ID, taskId);
        body.put(FIELD_FILE_NAME, fileName);
        body.put(FIELD_ATTEMPT, String.valueOf(attempt));
        if (!properties.getStreamKey().equals(streamKey)) {
            body.put(FIELD_ERROR, "");
        }
        ensureStreamExists(streamKey);
        RecordId id = redis.opsForStream().add(streamKey, body);
        log.debug("Задача {} ({}) попытка {} -> {} ({})", taskId, fileName, attempt, id, streamKey);
        return id == null ? null : id.getValue();
    }

    /** Идемпотентное создание группы: повторный вызов не считается ошибкой. */
    private void createGroupIfMissing(String streamKey) {
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), properties.getConsumerGroup());
            log.info("Consumer group '{}' создана для stream '{}'", properties.getConsumerGroup(), streamKey);
        } catch (RuntimeException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
            log.debug("Consumer group '{}' уже существует", properties.getConsumerGroup());
        }
    }

    /**
     * Признак уже существующей группы (BUSYGROUP).
     *
     * <p>Spring оборачивает ошибку Redis в несколько исключений, поэтому
     * сообщение ищется по всей цепочке причин.
     */
    static boolean isBusyGroup(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Пустой stream нельзя передать в XGROUP CREATE, поэтому добавляем служебную запись. */
    private void ensureStreamExists(String streamKey) {
        if (Boolean.TRUE.equals(redis.hasKey(streamKey))) {
            return;
        }
        redis.opsForStream().add(streamKey, Map.of(FIELD_BOOTSTRAP, "1"));
        log.debug("Stream '{}' создан со служебной записью", streamKey);
    }
}
