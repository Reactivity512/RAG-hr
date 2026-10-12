package com.github.reactivity512.rag.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.reactivity512.rag.queue.IndexingJobPublisher;
import com.github.reactivity512.rag.status.TaskStatusRepository;
import com.github.reactivity512.rag.storage.ResumeFileStorage;
import com.github.reactivity512.rag.vector.QdrantCollectionService;

/**
 * Административные операции: полная переиндексация каталога резюме.
 *
 * <p>Заменяет Python-овый {@code parser-cli}: раньше он делал drop+recreate
 * коллекции и заливал всё заново, теперь это отдельный эндпоинт. Операция
 * разрушительная, поэтому требует явного подтверждения
 * ({@code confirm=true}) и доступна только в профиле {@code api}.
 */
@RestController
@Profile("api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /** Форматы, которые принимает переиндексация. */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".txt", ".pdf", ".docx", ".rtf");

    private final ResumeFileStorage storage;
    private final QdrantCollectionService qdrant;
    private final IndexingJobPublisher jobPublisher;
    private final TaskStatusRepository statusRepository;

    public AdminController(
            ResumeFileStorage storage,
            QdrantCollectionService qdrant,
            IndexingJobPublisher jobPublisher,
            TaskStatusRepository statusRepository) {
        this.storage = storage;
        this.qdrant = qdrant;
        this.jobPublisher = jobPublisher;
        this.statusRepository = statusRepository;
    }

    /**
     * Пересоздаёт коллекцию и ставит в очередь все найденные резюме.
     *
     * <p>Коллекция пересоздаётся, потому что старая может содержать точки
     * прежней схемы (или посчитанные другой моделью) — иначе старые и новые
     * резюме окажутся несопоставимыми.
     */
    @PostMapping("/api/admin/reindex")
    public ReindexResponse reindex(
            @RequestParam(value = "confirm", defaultValue = "false") boolean confirm,
            @RequestParam(value = "recreate_collection", defaultValue = "true") boolean recreateCollection)
            throws IOException {

        if (!confirm) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Операция удалит текущий индекс. Повторите с confirm=true");
        }

        List<String> files = listSupportedFiles();
        log.info("Переиндексация: каталог {} содержит {} подходящих файлов",
                storage.resumesDir(), files.size());
        if (recreateCollection) {
            qdrant.recreateCollection();
            log.warn("Коллекция пересоздана, поставлено в очередь {} файлов", files.size());
        }

        List<String> taskIds = new ArrayList<>(files.size());
        for (String fileName : files) {
            String taskId = UUID.randomUUID().toString();
            statusRepository.createQueued(taskId, fileName);
            jobPublisher.enqueue(taskId, fileName);
            taskIds.add(taskId);
        }
        return new ReindexResponse(files.size(), recreateCollection, taskIds, Instant.now().toString());
    }

    /**
     * Файлы поддерживаемых форматов в каталоге резюме.
     *
     * <p>Обход рекурсивный: резюме удобно раскладывать по подкаталогам
     * (например, {@code samples/} с golden-корпусом).
     */
    private List<String> listSupportedFiles() throws IOException {
        Path dir = storage.resumesDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !path.toString().contains(".git"))
                    .map(path -> dir.relativize(path).toString().replace('\\', '/'))
                    .filter(name -> {
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
                    })
                    .sorted()
                    .toList();
        }
    }

    /** Результат постановки переиндексации. */
    public record ReindexResponse(
            int filesQueued,
            boolean collectionRecreated,
            List<String> taskIds,
            String startedAt) {
    }
}
