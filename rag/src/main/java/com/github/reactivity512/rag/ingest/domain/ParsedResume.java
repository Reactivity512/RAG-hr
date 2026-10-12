package com.github.reactivity512.rag.ingest.domain;

import java.util.List;

/**
 * Резюме, разобранное из выгрузки hh.ru.
 *
 * <p>Структурированно хранятся только те поля, по которым реально идёт работа
 * HR-специалиста: пол, должность, опыт, образование и навыки. Всё остальное
 * (контакты, формат работы, «обо мне», языки) остаётся внутри
 * {@link #fullText()} и попадает в эмбеддинг как часть документа.
 *
 * <p>{@code fullText} — нормализованный текст резюме: без колонтитулов и мусора
 * вёрстки, с приведёнными пробелами и восстановленными диапазонами дат. Именно
 * он даёт стабильный вектор независимо от того, пришёл файл как txt, pdf, docx
 * или rtf, и он же передаётся в LLM как контекст.
 */
public record ParsedResume(
        String name,
        String gender,
        String title,
        List<ExperienceEntry> experience,
        List<EducationEntry> education,
        List<String> skills,
        String fullText) {

    public ParsedResume {
        experience = List.copyOf(experience);
        education = List.copyOf(education);
        skills = List.copyOf(skills);
    }

    /** Отображаемое имя: кандидат, иначе желаемая должность. */
    public String displayLabel() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return title == null ? "" : title;
    }

    /** Есть ли обязательный минимум: должность, хотя бы одно место работы и навыки. */
    public List<String> missingRequiredFields() {
        List<String> missing = new java.util.ArrayList<>();
        if (title == null || title.isBlank()) {
            missing.add("Желаемая должность");
        }
        if (experience.isEmpty()) {
            missing.add("Опыт работы");
        }
        if (skills.isEmpty()) {
            missing.add("Навыки");
        }
        return missing;
    }
}
