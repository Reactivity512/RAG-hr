package com.github.reactivity512.rag.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.reactivity512.rag.api.dto.ApiDtos;
import com.github.reactivity512.rag.status.TaskState;
import com.github.reactivity512.rag.status.TaskStatus;
import com.github.reactivity512.rag.status.TaskStatusRepository;

/**
 * {@code GET /api/tasks} — статусы обработки резюме.
 *
 * <p>По умолчанию возвращаются только активные задачи (в очереди, в работе,
 * с ошибкой и повторяемые): готовые резюме видны в основном списке через
 * {@code /api/resumes}. Параметр {@code include_indexed=true} возвращает всё —
 * для аудита.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final List<TaskState> ACTIVE_STATES = List.of(
            TaskState.QUEUED, TaskState.PROCESSING, TaskState.RETRYING, TaskState.FAILED);

    private final TaskStatusRepository statusRepository;

    public TaskController(TaskStatusRepository statusRepository) {
        this.statusRepository = statusRepository;
    }

    @GetMapping
    public ApiDtos.TaskListResponse list(
            @RequestParam(value = "include_indexed", defaultValue = "false") boolean includeIndexed) {
        List<TaskStatus> tasks = statusRepository.list(includeIndexed ? null : ACTIVE_STATES);
        List<ApiDtos.TaskItem> items = new ArrayList<>(tasks.size());
        for (TaskStatus task : tasks) {
            items.add(new ApiDtos.TaskItem(
                    task.taskId(), task.filename(), task.status(),
                    task.uploadedAt(), task.resumeId(), task.error()));
        }
        return new ApiDtos.TaskListResponse(items, items.size());
    }
}
