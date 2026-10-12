package com.github.reactivity512.rag.ingest.domain;

/**
 * Одно место работы из блока «Опыт работы».
 *
 * <p>Порядок строк в выгрузках разный: в txt/rtf компания идёт перед диапазоном
 * дат, в PDF — после, поэтому все поля, кроме {@code period}, могут быть пустыми.
 */
public record ExperienceEntry(
        String period,
        String company,
        String city,
        String position,
        String description) {
}
