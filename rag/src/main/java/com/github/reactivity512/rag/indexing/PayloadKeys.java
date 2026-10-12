package com.github.reactivity512.rag.indexing;

/**
 * Ключи payload точки Qdrant.
 *
 * <p>Вынесены в константы, чтобы API, индексация и тесты не расходились
 * в написании русских ключей.
 */
public final class PayloadKeys {

    /** Имя кандидата. */
    public static final String NAME = "имя";
    /** Пол кандидата. */
    public static final String GENDER = "пол";
    /** Желаемая должность. */
    public static final String TITLE = "должность";
    /** Список мест работы. */
    public static final String EXPERIENCE = "опыт";
    /** Список образования. */
    public static final String EDUCATION = "образование";
    /** Навыки; по этому полю создан keyword-индекс. */
    public static final String SKILLS = "skills_list";
    /** Нормализованный полный текст резюме. */
    public static final String FULL_TEXT = "full_text";
    /** Имя файла в хранилище. */
    public static final String FILE_NAME = "file_name";
    /** Имя исходного загруженного файла. */
    public static final String ORIGINAL_FILE_NAME = "original_file_name";
    /** Формат исходного файла без точки. */
    public static final String SOURCE_FORMAT = "source_format";
    /** sha256 нормализованного текста. */
    public static final String CONTENT_HASH = "content_hash";
    public static final String UPLOADED_AT = "uploaded_at";
    public static final String INDEXED_AT = "indexed_at";

    private PayloadKeys() {
    }
}
