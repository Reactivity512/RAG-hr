package com.github.reactivity512.rag.ingest.parse;

import com.github.reactivity512.rag.ingest.domain.EducationEntry;
import com.github.reactivity512.rag.ingest.domain.ExperienceEntry;
import com.github.reactivity512.rag.ingest.domain.ParsedResume;

/**
 * Нормализованный полный текст резюме.
 *
 * <p>Это тот самый текст, который уходит в dense-эмбеддинг и в контекст LLM:
 * всё резюме целиком, а не только извлечённые поля. Нормализация нужна, чтобы
 * результат не зависел от формата исходного файла — в PDF порядок строк другой,
 * колонтитул попадает в середину, даты разорваны, а «Навыки» склеены со списком.
 *
 * <p>Собирается из уже распознанных блоков, поэтому служебные строки и мусор
 * вёрстки в текст попасть не могут. Внутренние переносы строк сжимаются в
 * пробелы: перенос зависит от формата, а смысл — нет.
 */
public final class CanonicalText {

    private CanonicalText() {
    }

    public static String render(ParsedResume resume) {
        StringBuilder sb = new StringBuilder(2048);
        appendValue(sb, resume.name());
        appendValue(sb, resume.gender());

        if (resume.title() != null && !resume.title().isBlank()) {
            section(sb, "Желаемая должность");
            sb.append(squash(resume.title())).append('\n');
        }

        if (!resume.experience().isEmpty()) {
            section(sb, "Опыт работы");
            for (ExperienceEntry entry : resume.experience()) {
                sb.append("- ");
                sb.append(joinNonBlank(entry.period(), entry.position(), entry.company(), entry.city()));
                if (entry.description() != null && !entry.description().isBlank()) {
                    sb.append('\n').append(squash(entry.description()));
                }
                sb.append('\n');
            }
        }

        if (!resume.education().isEmpty()) {
            section(sb, "Образование");
            for (EducationEntry entry : resume.education()) {
                String line = joinNonBlank(entry.institution(), entry.specialty(), entry.year());
                if (!line.isBlank()) {
                    sb.append("- ").append(line).append('\n');
                }
            }
        }

        if (!resume.skills().isEmpty()) {
            section(sb, "Навыки");
            sb.append(String.join("; ", resume.skills())).append('\n');
        }

        return sb.toString().trim();
    }

    private static void appendValue(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(squash(value)).append('\n');
        }
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n').append(title).append(":\n");
    }

    /** Внутренние пробелы и переводы строк сжимаются: переносы не должны влиять на результат. */
    private static String squash(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(squash(part));
            }
        }
        return sb.toString();
    }
}
