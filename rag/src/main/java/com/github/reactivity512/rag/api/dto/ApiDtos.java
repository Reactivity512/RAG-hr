package com.github.reactivity512.rag.api.dto;

import java.util.List;

import com.github.reactivity512.rag.status.TaskState;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * DTO HTTP-контракта.
 *
 * <p>Имена полей — snake_case: это контракт, к которому привязан существующий
 * фронтенд ({@code top_k}, {@code found_resumes_count}, {@code file_name},
 * {@code skills_list}, {@code uploaded_at}, {@code task_id} …). Настройка
 * задана аннотацией на каждом типе, а не глобально, чтобы не влиять на
 * сериализацию внутренних структур (payload Qdrant, статусы в Redis).
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SearchRequest(String query, Integer topK, List<String> requiredSkills) {

        public int topKOrDefault() {
            return topK == null || topK <= 0 ? 5 : Math.min(topK, 50);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SearchResponse(String answer, int foundResumesCount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UploadResponse(String taskId, String filename, String status) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResumeListItem(
            String id,
            String fileName,
            String name,
            String title,
            String salary,
            String workFormat,
            List<String> skillsList,
            String sourceFormat,
            String uploadedAt,
            String indexedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResumeListResponse(List<ResumeListItem> items, int total) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExperienceItem(
            String period,
            String company,
            String city,
            String position,
            String description) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EducationItem(String year, String institution, String specialty) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResumeDetail(
            String id,
            String fileName,
            String originalFileName,
            String sourceFormat,
            String name,
            String gender,
            String title,
            List<ExperienceItem> experience,
            List<EducationItem> education,
            List<String> skillsList,
            String fullText,
            String uploadedAt,
            String indexedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskItem(
            String taskId,
            String filename,
            TaskState status,
            String uploadedAt,
            String resumeId,
            String error) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskListResponse(List<TaskItem> items, int total) {
    }

    /** Единый формат ошибки: FastAPI отдавал {@code {"detail": "..."}}. */
    public record ErrorResponse(String detail) {
    }
}
