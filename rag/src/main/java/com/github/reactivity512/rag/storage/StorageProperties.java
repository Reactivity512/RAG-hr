package com.github.reactivity512.rag.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры хранилища оригиналов резюме.
 *
 * <p>Оригиналы сохраняются: эмбеддинг и контекст LLM строятся по извлечённому
 * тексту, а исходный файл (pdf/docx/rtf) доступен для скачивания из UI.
 */
@ConfigurationProperties(prefix = "rag.storage")
public class StorageProperties {

    /** Каталог с оригиналами резюме. */
    private String resumesDir = "./resumes";

    /** Максимальный размер загружаемого файла. */
    private long maxUploadBytes = 512 * 1024;

    public String getResumesDir() {
        return resumesDir;
    }

    public void setResumesDir(String resumesDir) {
        this.resumesDir = resumesDir;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }
}
