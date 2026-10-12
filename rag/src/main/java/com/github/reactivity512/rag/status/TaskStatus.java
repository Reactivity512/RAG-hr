package com.github.reactivity512.rag.status;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Запись о задаче обработки в Redis (hash {@code resumes:tasks}).
 *
 * <p>Ключи JSON — snake_case: так записывала Python-версия, и это же
 * читает фронтенд через {@code /api/tasks}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskStatus(
        String taskId,
        String filename,
        TaskState status,
        String uploadedAt,
        String resumeId,
        String error) {

    public TaskStatus withState(TaskState newState, String newResumeId, String newError) {
        return new TaskStatus(taskId, filename, newState, uploadedAt, newResumeId, newError);
    }
}
