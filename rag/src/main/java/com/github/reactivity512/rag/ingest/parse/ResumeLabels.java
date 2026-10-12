package com.github.reactivity512.rag.ingest.parse;

import java.util.List;
import java.util.Set;

/**
 * Лексика hh.ru-выгрузки: заголовки разделов и подписей.
 *
 * <p>Заголовки встречаются в двух видах — с двоеточием и без, и иногда
 * склеены со значением в одной строке. Все сравнения идут по нормализованной
 * строке (регистр не важен, хвостовое двоеточие срезано).
 */
public final class ResumeLabels {

    /** Заголовок раздела «желаемая должность» — без двоеточия. */
    public static final String DESIRED_POSITION = "желаемая должность и зарплата";

    /** Маркер блока опыта: «Опыт работы — 9 лет 5 месяцев». */
    public static final String EXPERIENCE = "опыт работы";

    /** Раздел дополнительной информации (родитель для «Обо мне»). */
    public static final String ADDITIONAL_INFO = "дополнительная информация";

    public static final String EDUCATION = "образование";
    public static final String SKILLS = "навыки";
    public static final String LANGUAGES = "знание языков";
    public static final String ABOUT = "обо мне";
    public static final String SPECIALIZATIONS = "специализации";

    /** Заголовки, завершающие блок опыта. */
    public static final Set<String> EXPERIENCE_TERMINATORS = Set.of(EDUCATION);

    /** Заголовки, завершающие блок образования. */
    public static final Set<String> EDUCATION_TERMINATORS = Set.of(SKILLS, LANGUAGES, ADDITIONAL_INFO);

    /** Подписи «ключ: значение» (нормализованный ключ → поле). */
    public static final String KEY_LOCATION = "проживает";
    public static final String KEY_CITIZENSHIP = "гражданство";
    public static final String KEY_EMPLOYMENT_TYPE = "тип занятости";
    public static final String KEY_WORK_FORMAT = "формат работы";
    public static final String KEY_TRAVEL_TIME = "желательное время в пути до работы";

    /** Служебные строки шапки, которые никогда не являются именем кандидата. */
    public static final List<String> GENDER_MARKERS = List.of("мужчина", "женщина");

    public static final List<String> RELOCATION_MARKERS = List.of(
            "не готов к переезду", "готов к переезду",
            "не готов к командировкам", "готов к командировкам",
            "не готов к переезду, не готов к командировкам");

    private ResumeLabels() {
    }

    /** Нормализация строки для сравнения с заголовком: нижний регистр, без хвостового «:». */
    public static String normalizeHeader(String line) {
        if (line == null) {
            return "";
        }
        String value = line.trim().toLowerCase(java.util.Locale.ROOT);
        while (value.endsWith(":")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    /** Строка начинается с указанного заголовка (с двоеточием, без него или с пробелом). */
    public static boolean startsWithHeader(String line, String header) {
        String normalized = normalizeHeader(line);
        return normalized.equals(header) || normalized.startsWith(header + " ");
    }

    /** Значение «ключ: значение»; null, если строка не начинается с этого ключа. */
    public static String valueAfterKey(String line, String key) {
        String normalized = normalizeHeader(line);
        if (!normalized.startsWith(key + ":")) {
            return null;
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String value = line.substring(colon + 1).trim();
        return value.isEmpty() ? null : value;
    }
}
