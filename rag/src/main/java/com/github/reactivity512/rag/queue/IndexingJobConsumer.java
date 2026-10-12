package com.github.reactivity512.rag.queue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.github.reactivity512.rag.ingest.ResumeFormatException;
import com.github.reactivity512.rag.status.TaskState;
import com.github.reactivity512.rag.status.TaskStatusRepository;

import jakarta.annotation.PreDestroy;

/**
 * Воркер очереди: читает задачи из Redis Stream через consumer group.
 *
 * <p>Семантика at-least-once: запись подтверждается ({@code XACK}) только после
 * обработки — аналог {@code task_acks_late} в Celery. Если воркер упал между
 * чтением и подтверждением, запись остаётся в pending group и будет
 * переобработана после перезапуска; повторная обработка безопасна, потому что
 * id точки детерминированный (sha256 текста), то есть upsert идемпотентен.
 *
 * <p>Терминальные ошибки (формат файла, нет обязательных разделов) не
 * повторяются, временные (Ollama или Qdrant недоступны) — повторяются, а после
 * {@link QueueProperties#getMaxAttempts()} попыток задача уезжает в DLQ.
 *
 * <p>Цикл стартует по {@link ApplicationReadyEvent}: к этому моменту контекст
 * полностью поднят, соединения Redis готовы, а startup-проверки выполнены.
 * Попытка читать из потока в {@code @PostConstruct} приводит к гонке с
 * инициализацией соединений Redis.
 */
@Component
@EnableConfigurationProperties(QueueProperties.class)
@ConditionalOnProperty(name = "rag.queue.enabled", havingValue = "true")
public class IndexingJobConsumer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(IndexingJobConsumer.class);
    private static final long IDLE_SLEEP_MS = 200;

    private final StringRedisTemplate redis;
    private final QueueProperties properties;
    private final ResumeIndexingJobService jobService;
    private final TaskStatusRepository statusRepository;
    private final IndexingJobPublisher publisher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private Thread worker;

    public IndexingJobConsumer(
            StringRedisTemplate redis,
            QueueProperties properties,
            ResumeIndexingJobService jobService,
            TaskStatusRepository statusRepository,
            IndexingJobPublisher publisher) {
        this.redis = redis;
        this.properties = properties;
        this.jobService = jobService;
        this.statusRepository = statusRepository;
        this.publisher = publisher;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    /** Запускает цикл чтения в отдельном потоке. */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        worker = new Thread(this::loop, "resume-indexing-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("Воркер очереди запущен: consumer={}, stream={}, group={}",
                consumerName, properties.getStreamKey(), properties.getConsumerGroup());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        log.info("Воркер очереди остановлен");
    }

    /** Один цикл чтения и обработки; вынесен отдельно, чтобы можно было тестировать. */
    public int processOnce() {
        List<MapRecord<String, Object, Object>> records = readBatch();
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (MapRecord<String, Object, Object> record : records) {
            handle(record);
            processed++;
        }
        return processed;
    }

    private void loop() {
        while (running.get()) {
            try {
                int processed = processOnce();
                if (processed == 0) {
                    sleepQuietly(IDLE_SLEEP_MS);
                }
            } catch (RuntimeException e) {
                log.warn("Сбой цикла воркера: {}", e.getMessage());
                sleepQuietly(properties.getPollBlockMs());
            }
        }
    }

    private List<MapRecord<String, Object, Object>> readBatch() {
        StreamReadOptions options = StreamReadOptions.empty()
                .count(properties.getBatchSize())
                .block(Duration.ofMillis(properties.getPollBlockMs()));
        try {
            return redis.opsForStream().read(
                    Consumer.from(properties.getConsumerGroup(), consumerName),
                    options,
                    StreamOffset.create(properties.getStreamKey(), ReadOffset.lastConsumed()));
        } catch (RuntimeException e) {
            log.warn("Чтение из очереди не удалось: {}", rootMessage(e));
            return List.of();
        }
    }

    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        Object taskIdRaw = fields.get(IndexingJobPublisher.FIELD_TASK_ID);
        Object fileNameRaw = fields.get(IndexingJobPublisher.FIELD_FILE_NAME);

        // Служебная запись, которой создавался пустой stream
        if (taskIdRaw == null || fileNameRaw == null) {
            ack(record);
            return;
        }
        String taskId = taskIdRaw.toString();
        String fileName = fileNameRaw.toString();
        int attempt = parseAttempt(fields.get(IndexingJobPublisher.FIELD_ATTEMPT));

        try {
            String resumeId = jobService.process(taskId, fileName);
            ack(record);
            log.info("Задача {} обработана: файл={} резюме={}", taskId, fileName, resumeId);
        } catch (ResumeFormatException e) {
            // Терминальная ошибка: повторять бессмысленно, статус уже FAILED
            ack(record);
            log.warn("Задача {} отклонена по формату: {}", taskId, e.getMessage());
        } catch (RuntimeException e) {
            retryOrDeadLetter(record, taskId, fileName, attempt, e);
        }
    }

    private void retryOrDeadLetter(MapRecord<String, Object, Object> record, String taskId,
            String fileName, int attempt, RuntimeException error) {
        ack(record);
        String message = rootMessage(error);
        if (attempt < properties.getMaxAttempts()) {
            publisher.retry(taskId, fileName, attempt + 1);
            statusRepository.update(taskId, fileName, TaskState.QUEUED, null, message);
            log.warn("Задача {} будет повторена (попытка {}/{})", taskId, attempt + 1,
                    properties.getMaxAttempts());
            return;
        }
        publisher.toDeadLetter(taskId, fileName, message);
        statusRepository.update(taskId, fileName, TaskState.FAILED, null, message);
        log.error("Задача {} исчерпала попытки ({}): {}", taskId, properties.getMaxAttempts(), message);
    }

    private void ack(MapRecord<String, Object, Object> record) {
        try {
            redis.opsForStream().acknowledge(
                    properties.getStreamKey(), properties.getConsumerGroup(), record.getId());
        } catch (RuntimeException e) {
            log.warn("Не удалось подтвердить запись {}: {}", record.getId(), rootMessage(e));
        }
    }

    private int parseAttempt(Object raw) {
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Самое информативное сообщение из цепочки причин. */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
