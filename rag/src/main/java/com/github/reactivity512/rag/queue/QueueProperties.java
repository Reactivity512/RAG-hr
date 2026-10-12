package com.github.reactivity512.rag.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры очереди задач на Redis Streams.
 *
 * <p>Redis остаётся единственной инфраструктурой для очереди: stream и
 * consumer group дают at-least-once доставку, {@code XACK} — подтверждение
 * после обработки (аналог {@code task_acks_late} в Celery), а отдельный
 * stream-DLQ принимает задачи, исчерпавшие попытки.
 */
@ConfigurationProperties(prefix = "rag.queue")
public class QueueProperties {

    /** Stream с задачами индексации. */
    private String streamKey = "resumes:jobs";

    /** Имя consumer group воркеров. */
    private String consumerGroup = "workers";

    /** Stream для задач, исчерпавших попытки. */
    private String deadLetterKey = "resumes:jobs:dlq";

    /** Сколько попыток даётся задаче. */
    private int maxAttempts = 3;

    /** Сколько задач забирать за одно чтение. */
    private int batchSize = 4;

    /** Блокирующее ожидание новых задач, мс. */
    private long pollBlockMs = 2000;

    /** Через сколько миллисекунд считать незавершённую задачу зависшей. */
    private long claimIdleMs = 60_000;

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getDeadLetterKey() {
        return deadLetterKey;
    }

    public void setDeadLetterKey(String deadLetterKey) {
        this.deadLetterKey = deadLetterKey;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPollBlockMs() {
        return pollBlockMs;
    }

    public void setPollBlockMs(long pollBlockMs) {
        this.pollBlockMs = pollBlockMs;
    }

    public long getClaimIdleMs() {
        return claimIdleMs;
    }

    public void setClaimIdleMs(long claimIdleMs) {
        this.claimIdleMs = claimIdleMs;
    }
}
