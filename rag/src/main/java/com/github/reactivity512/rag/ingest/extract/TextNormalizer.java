package com.github.reactivity512.rag.ingest.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Нормализация извлечённого текста.
 *
 * <p>Все источники (txt/pdf/docx/rtf) приводятся к одному виду:
 * BOM убран, переводы строк — {@code \n}, неразрывные пробелы заменены
 * обычными, хвостовые пробелы срезаны. Это обязательное условие того,
 * чтобы парсер не зависел от формата файла.
 */
public final class TextNormalizer {

    private static final String NBSP = "\u00A0";
    private static final String NARROW_NBSP = "\u202F";
    private static final String ZERO_WIDTH = "\u200B";
    private static final String BOM = "\uFEFF";

    private TextNormalizer() {
    }

    /** Полная нормализация многострочного текста. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace(BOM, "");
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace(NBSP, " ").replace(NARROW_NBSP, " ").replace(ZERO_WIDTH, "");
        // Вертикальный таб и прочие разделители строк из PDF
        text = text.replace("\u000B", "\n").replace("\f", "\n");
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            sb.append(stripTrailing(line)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Нормализация одной строки-значения: неразрывные пробелы заменены,
     * концы обрезаны. Возвращает null для пустой строки.
     *
     * <p>Внутренние пробелы намеренно не сжимаются: в PDF-выгрузке навыки
     * разделены сериями пробелов, и эта информация нужна парсеру.
     * Поля, где пробелы не значимы, сжимают их сами.
     */
    public static String cleanLine(String line) {
        if (line == null) {
            return null;
        }
        String cleaned = line.replace(BOM, "").replace(NBSP, " ").replace(NARROW_NBSP, " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Строки текста без пустых, с очищенными значениями. */
    public static List<String> toLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : normalize(text).split("\n", -1)) {
            String line = cleanLine(raw);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
