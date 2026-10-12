package com.github.reactivity512.rag.ingest.domain;

/**
 * Одно образование из блока «Образование».
 *
 * <p>Блок необязательный: в PDF-выгрузке он может отсутствовать, поэтому
 * в результате допустим пустой список.
 */
public record EducationEntry(
        String year,
        String institution,
        String specialty) {
}
