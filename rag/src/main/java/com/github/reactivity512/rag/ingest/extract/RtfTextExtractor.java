package com.github.reactivity512.rag.ingest.extract;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.stereotype.Component;

/**
 * Извлечение текста из RTF.
 *
 * <p>POI RTF не читает (в {@code poi-scratchpad} парсера RTF нет, а
 * {@code HWPFDocument} на RTF падает с «The document is really a RTF file»),
 * поэтому реализован минимальный, но достаточный для выгрузок hh.ru разбор:
 *
 * <ul>
 *   <li>{@code \\uNNNN} — Unicode-escape (основной способ записи кириллицы);
 *   <li>{@code \\ucN} — сколько символов-заменителей пропускать после escape;
 *   <li>пропуск бинарных групп: {@code \\pict} и вложенных в неё hex-данных;
 *   <li>пропуск колонтитулов {@code \\header}/{@code \\footer} — в PDF они
 *       попадают в основной текст, здесь их быть не должно;
 *   <li>{@code \\par}/{@code \\line} — перевод строки, {@code \\tab} — таб;
 *   <li>прочая управляющая разметка игнорируется.
 * </ul>
 */
@Component
public class RtfTextExtractor {

    /** Группы, содержимое которых текстом не является. */
    private static final java.util.Set<String> SKIPPED_DESTINATIONS = java.util.Set.of(
            "pict", "header", "footer", "headerl", "headerr", "headerf", "footerl",
            "footerr", "footerf", "fonttbl", "colortbl", "stylesheet", "info",
            "listtable", "listoverridetable", "revtbl", "generator", "themedata",
            "colorschememapping", "latentstyles", "datastore", "mmathPr", "xmlnstbl");

    /** Управляющие слова, которые превращаются в перевод строки. */
    private static final java.util.Set<String> LINE_BREAKS = java.util.Set.of("par", "line", "sect");

    public String extract(byte[] content) {
        String raw = new String(content, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder(raw.length() / 4);
        Deque<Boolean> skipStack = new ArrayDeque<>();
        boolean skipping = false;
        int fieldDepth = -1;
        int braceDepth = 0;
        int ucSkip = 1;
        Charset charset = null;
        int index = 0;
        int length = raw.length();

        while (index < length) {
            char ch = raw.charAt(index);

            if (ch == '{') {
                braceDepth++;
                skipStack.push(false);
                index++;
                continue;
            }
            if (ch == '}') {
                if (!skipStack.isEmpty() && Boolean.TRUE.equals(skipStack.pop())) {
                    skipping = false;
                }
                braceDepth = Math.max(0, braceDepth - 1);
                if (fieldDepth >= 0 && braceDepth < fieldDepth) {
                    // Группа поля завершилась — выходим из режима «читаем только результат»
                    fieldDepth = -1;
                    skipping = false;
                }
                index++;
                continue;
            }
            if (ch != '\\') {
                if (!skipping) {
                    out.append(ch);
                }
                index++;
                continue;
            }

            // Управляющее слово или символ
            index++;
            if (index >= length) {
                break;
            }
            char next = raw.charAt(index);

            if (next == '\\' || next == '{' || next == '}') {
                if (!skipping) {
                    out.append(next);
                }
                index++;
                continue;
            }

            if (next == '~') {
                // Неразрывный пробел
                if (!skipping) {
                    out.append(' ');
                }
                index++;
                continue;
            }

            if (next == ' ') {
                // Экранированный пробел: значимый символ, а не разделитель
                if (!skipping) {
                    out.append(' ');
                }
                index++;
                continue;
            }

            if (next == '-' || next == '_') {
                // Необязательный дефис / неразрывный дефис
                index++;
                continue;
            }

            if (next == '\'') {
                // \'hh — байт в кодировке документа
                if (index + 2 < length && !skipping) {
                    String hex = raw.substring(index + 1, index + 3);
                    try {
                        int value = Integer.parseInt(hex, 16);
                        out.append(decodeByte(value, charset));
                    } catch (NumberFormatException ignored) {
                        // повреждённая escape-последовательность — пропускаем
                    }
                }
                index += 3;
                continue;
            }

            if (!Character.isLetter(next)) {
                // Управляющий символ: \* \~ \- \_ и прочее
                index++;
                continue;
            }

            // Читаем имя управляющего слова
            int wordStart = index;
            while (index < length && Character.isLetter(raw.charAt(index))) {
                index++;
            }
            String word = raw.substring(wordStart, index);

            // Числовой параметр (со знаком)
            int paramStart = index;
            if (index < length && (raw.charAt(index) == '-' || Character.isDigit(raw.charAt(index)))) {
                index++;
                while (index < length && Character.isDigit(raw.charAt(index))) {
                    index++;
                }
            }
            String paramText = raw.substring(paramStart, index);
            Integer param = paramText.isEmpty() ? null : parseSafe(paramText);

            // Пробел-разделитель после управляющего слова
            if (index < length && raw.charAt(index) == ' ') {
                index++;
            }

            if ("uc".equals(word) && param != null) {
                ucSkip = Math.max(0, param);
                continue;
            }
            if ("ansicpg".equals(word) && param != null && charset == null) {
                charset = codepageCharset(param);
                continue;
            }
            if ("fldrslt".equals(word)) {
                // Начало отображаемого результата поля: снова читаем текст
                fieldDepth = braceDepth;
                skipping = false;
                continue;
            }
            if (fieldDepth >= 0) {
                // Внутри поля всё, кроме fldrslt, — служебное (адрес гиперссылки и т.п.)
                continue;
            }
            if ("field".equals(word)) {
                // Поле целиком пропускаем до fldrslt: иначе в текст попадут
                // служебные слова HYPERLINK и адрес ссылки
                skipping = true;
                continue;
            }
            if (SKIPPED_DESTINATIONS.contains(word)) {
                // Внутри группы помечаем пропуск; вне группы — просто игнорируем
                if (!skipStack.isEmpty()) {
                    skipStack.pop();
                    skipStack.push(true);
                    skipping = true;
                }
                continue;
            }
            if (LINE_BREAKS.contains(word)) {
                if (!skipping) {
                    out.append('\n');
                }
                continue;
            }
            if ("tab".equals(word)) {
                if (!skipping) {
                    out.append('\t');
                }
                continue;
            }
            if ("emdash".equals(word)) {
                if (!skipping) {
                    out.append('\u2014');
                }
                continue;
            }
            if ("endash".equals(word)) {
                if (!skipping) {
                    out.append('\u2013');
                }
                continue;
            }
            if ("bullet".equals(word)) {
                if (!skipping) {
                    out.append('\u2022');
                }
                continue;
            }
            if ("u".equals(word) && param != null) {
                if (!skipping) {
                    int codePoint = param < 0 ? param + 65536 : param;
                    out.append((char) codePoint);
                }
                // Пропускаем символы-заменители, которые идут сразу после escape-последовательности
                int skipped = 0;
                while (skipped < ucSkip && index < length) {
                    char c = raw.charAt(index);
                    if (c == '\\') {
                        break;
                    }
                    if (c == '{' || c == '}') {
                        break;
                    }
                    index++;
                    skipped++;
                }
                continue;
            }
            // Остальные управляющие слова игнорируем
        }

        return out.toString();
    }

    private static Integer parseSafe(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Байт {@code \'hh} → символ в кодировке документа. */
    private static char decodeByte(int value, Charset charset) {
        Charset effective = charset == null ? StandardCharsets.ISO_8859_1 : charset;
        byte[] single = {(byte) value};
        String decoded = new String(single, effective);
        return decoded.isEmpty() ? '?' : decoded.charAt(0);
    }

    /** {@code ansicpg} → Java-кодировка; для неизвестных — windows-1251. */
    private static Charset codepageCharset(int codepage) {
        String name = switch (codepage) {
            case 1250 -> "windows-1250";
            case 1251 -> "windows-1251";
            case 1252 -> "windows-1252";
            case 1253 -> "windows-1253";
            case 1254 -> "windows-1254";
            case 1255 -> "windows-1255";
            case 1256 -> "windows-1256";
            case 1257 -> "windows-1257";
            case 1258 -> "windows-1258";
            case 866 -> "IBM866";
            case 65001 -> "UTF-8";
            default -> null;
        };
        if (name == null) {
            return Charset.forName("windows-1251");
        }
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return Charset.forName("windows-1251");
        }
    }
}
