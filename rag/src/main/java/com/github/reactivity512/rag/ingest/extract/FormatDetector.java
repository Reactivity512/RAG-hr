package com.github.reactivity512.rag.ingest.extract;

import java.nio.charset.StandardCharsets;

import com.github.reactivity512.rag.ingest.SourceFormat;

/**
 * Определение формата файла по содержимому (магические байты).
 *
 * <p>Расширению доверять нельзя: в этом проекте файл {@code .doc} оказался RTF.
 */
public final class FormatDetector {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] RTF_MAGIC = {'{', '\\', 'r', 't', 'f'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private FormatDetector() {
    }

    /**
     * @return определённый формат или null, если содержимое не распознано
     */
    public static SourceFormat detect(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        int offset = bomLength(content);
        if (startsWith(content, offset, PDF_MAGIC)) {
            return SourceFormat.PDF;
        }
        if (startsWith(content, offset, RTF_MAGIC)) {
            return SourceFormat.RTF;
        }
        if (startsWith(content, offset, ZIP_MAGIC)) {
            // OOXML-контейнер; .docx отличаем по content types внутри архива
            return looksLikeDocx(content) ? SourceFormat.DOCX : null;
        }
        return isProbablyText(content) ? SourceFormat.TXT : null;
    }

    /** BOM (UTF-8 / UTF-16) в начале файла. */
    private static int bomLength(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            return 2;
        }
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            return 2;
        }
        return 0;
    }

    private static boolean startsWith(byte[] content, int offset, byte[] magic) {
        if (content.length - offset < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * OOXML — это zip; ищем маркер wordprocessingml в первых байтах архива,
     * чтобы не принять за резюме произвольный docx-подобный контейнер.
     */
    private static boolean looksLikeDocx(byte[] content) {
        int limit = Math.min(content.length, 4096);
        String head = new String(content, 0, limit, StandardCharsets.ISO_8859_1);
        return head.contains("word/") || head.contains("wordprocessingml");
    }

    /**
     * Текст без управляющих символов (кроме переводов строк и табов)
     * и без нулевых байтов — иначе это бинарник.
     */
    private static boolean isProbablyText(byte[] content) {
        int limit = Math.min(content.length, 8192);
        int control = 0;
        for (int i = 0; i < limit; i++) {
            int b = content[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            boolean allowed = b == '\n' || b == '\r' || b == '\t' || b >= 0x20;
            if (!allowed) {
                control++;
            }
        }
        return control * 100 / limit < 5;
    }
}
