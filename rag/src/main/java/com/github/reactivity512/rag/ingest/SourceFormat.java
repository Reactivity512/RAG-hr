package com.github.reactivity512.rag.ingest;

import java.util.Locale;

/**
 * Формат исходного файла резюме.
 *
 * <p>Определяется по магическим байтам, а не по расширению: на практике
 * встречается RTF, сохранённый под именем {@code .doc} (именно так пришёл
 * тестовый файл этого проекта).
 */
public enum SourceFormat {

    TXT("text/plain", ".txt"),
    PDF("application/pdf", ".pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    RTF("application/rtf", ".rtf");

    private final String contentType;
    private final String extension;

    SourceFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    /** Формат, соответствующий расширению имени файла; null — неизвестное расширение. */
    public static SourceFormat fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (SourceFormat format : values()) {
            if (lower.endsWith(format.extension)) {
                return format;
            }
        }
        return null;
    }
}
