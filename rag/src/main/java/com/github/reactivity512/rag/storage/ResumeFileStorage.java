package com.github.reactivity512.rag.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Хранилище оригиналов резюме на диске.
 *
 * <p>Отвечает за безопасное имя файла (без путей и спецсимволов), разрешение
 * коллизий имён и доступ к сохранённым оригиналам для скачивания.
 */
@Service
@EnableConfigurationProperties(StorageProperties.class)
public class ResumeFileStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeFileStorage.class);

    private final StorageProperties properties;

    public ResumeFileStorage(StorageProperties properties) {
        this.properties = properties;
    }

    public Path resumesDir() {
        return Path.of(properties.getResumesDir()).toAbsolutePath().normalize();
    }

    public long maxUploadBytes() {
        return properties.getMaxUploadBytes();
    }

    /**
     * Сохраняет оригинал файла, возвращая фактическое имя.
     *
     * <p>Если файл с таким именем уже есть, добавляется числовой суффикс:
     * {@code ivanov.txt} → {@code ivanov_2.txt}.
     */
    public String save(String originalName, byte[] content) {
        String safeName = sanitize(originalName);
        Path dir = resumesDir();
        try {
            Files.createDirectories(dir);
            Path target = resolveCollision(dir.resolve(safeName));
            Files.write(target, content);
            String savedName = target.getFileName().toString();
            log.debug("Оригинал сохранён: {} ({} байт)", savedName, content.length);
            return savedName;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить резюме " + safeName, e);
        }
    }

    /** Читает сохранённый оригинал. */
    public byte[] read(String fileName) {
        Path path = resolveExisting(fileName);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать резюме " + fileName, e);
        }
    }

    /** Удаляет оригинал, если он есть. */
    public boolean delete(String fileName) {
        try {
            Path path = resolveExisting(fileName);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Не удалось удалить файл {}: {}", fileName, e.getMessage());
            return false;
        }
    }

    /** Существует ли оригинал. */
    public boolean exists(String fileName) {
        try {
            return Files.isRegularFile(resolveExisting(fileName));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Проверяет, что имя указывает на файл внутри каталога резюме.
     * Защита от выхода за пределы каталога через {@code ..} и абсолютные пути.
     */
    private Path resolveExisting(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("Не указано имя файла");
        }
        Path base = resumesDir();
        Path resolved = base.resolve(fileName).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Недопустимое имя файла: " + fileName);
        }
        return resolved;
    }

    private Path resolveCollision(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        int index = 2;
        Path candidate;
        do {
            candidate = path.resolveSibling(base + "_" + index + extension);
            index++;
        } while (Files.exists(candidate));
        return candidate;
    }

    /**
     * Очищает имя файла: убирает путь, диакритику, служебные символы
     * и пробелы, приводит расширение к нижнему регистру.
     */
    public static String sanitize(String rawName) {
        String name = rawName == null ? "" : rawName;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFKD);
        name = name.replaceAll("\\p{M}", "");
        name = name.replaceAll("[^\\w\\s\\-.\\u0400-\\u04FF]", "");
        name = name.replaceAll("\\s+", "_").replaceAll("^[._]+|[._]+$", "");
        if (name.isEmpty()) {
            name = "resume";
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot) + "." + name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return name;
    }

    /** Текстовое содержимое оригинала (используется в тестах и админ-операциях). */
    public String readAsText(String fileName) {
        return new String(read(fileName), StandardCharsets.UTF_8);
    }
}
