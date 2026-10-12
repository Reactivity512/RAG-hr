package com.github.reactivity512.rag.ingest.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.github.reactivity512.rag.ingest.ResumeFormatException;
import com.github.reactivity512.rag.ingest.domain.EducationEntry;
import com.github.reactivity512.rag.ingest.domain.ExperienceEntry;
import com.github.reactivity512.rag.ingest.domain.ParsedResume;
import com.github.reactivity512.rag.ingest.extract.TextNormalizer;

/**
 * Парсер резюме в формате hh.ru-выгрузки.
 *
 * <p>Ключевое требование: результат не зависит от формата исходного файла.
 * Один и тот же документ в txt, pdf, docx и rtf даёт разный порядок строк
 * (в PDF колонтитул попадает в середину текста, даты разорваны, «Навыки»
 * склеены со списком), поэтому парсер не полагается на порядок строк, а
 * классифицирует строки по содержимому.
 *
 * <p>Структурированно извлекаются только рабочие поля: пол, должность, опыт,
 * образование, навыки. Остальное остаётся в нормализованном тексте, который
 * уходит в эмбеддинг.
 */
@Component
public class ResumeParser {

    /** Строка-месяц: «Июнь 2022», «Декабрь 2025 —». */
    private static final Pattern MONTH_LINE = Pattern.compile(
            "(?iu)^(?:январ[ья]|феврал[ья]|март[а]?|апрел[ья]|май|мая|июн[ья]|июл[ья]"
                    + "|август[а]?|сентябр[ья]|октябр[ья]|ноябр[ья]|декабр[ья])"
                    + "\\s+\\d{4}(?:\\s*[\u2014\u2013-]\\s*)?$");
    /** Полный диапазон дат одной строкой. */
    private static final Pattern DATE_RANGE = Pattern.compile(
            "(?iu)^[а-яё]+\\s+\\d{4}\\s*[\u2014\u2013-]\\s*[а-яё]+\\s+\\d{4}");
    /** Длительность: «9 лет 5 месяцев», «1 год 11 месяцев», «3 года». */
    private static final Pattern DURATION = Pattern.compile(
            "(?iu)^(?:\\d+\\s+(?:год|года|лет)(?:\\s+\\d+\\s+месяц(?:а|ев)?)?"
                    + "|\\d+\\s+месяц(?:а|ев)?)$");
    private static final Pattern GRADUATION_YEAR = Pattern.compile("^(19|20)\\d{2}$");
    private static final Pattern DOC_UPDATED = Pattern.compile("(?iu)Резюме\\s+обновлено\\s+(.+)");
    private static final Pattern EXPERIENCE_TOTAL =
            Pattern.compile("(?iu)^Опыт\\s+работы\\s*[\u2014\u2013-]\\s*(.+)$");
    private static final Pattern PHONE = Pattern.compile("\\+?[\\d][\\d\\s()\\-]{8,}\\d");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern MESSENGER =
            Pattern.compile("(?iu)^(telegram|whatsapp|skype|viber|телеграм)\\s*:");

    private static final Set<String> DEGREE_MARKERS = Set.of(
            "высшее", "бакалавр", "магистр", "специалист", "кандидат наук", "доктор наук",
            "среднее профессиональное", "неоконченное высшее", "аспирантура");

    private static final List<String> INSTITUTION_KEYWORDS = List.of(
            "университет", "институт", "академи", "колледж", "училище", "техникум",
            "школа", "лицей", "university", "college", "institute");

    /**
     * Корни названий ролей: по ним строка опознаётся как должность, а не как
     * компания.
     */
    private static final List<String> ROLE_KEYWORDS = List.of(
            "разработ", "программист", "инженер", "developer", "engineer", "архитектор",
            "аналитик", "менеджер", "manager", "тестировщик", "qa", "devops", "администратор",
            "консультант", "специалист", "руководител", "директор", "стажер", "стажёр",
            "fullstack", "backend", "frontend", "teamlead", "team lead", "sre");

    private static final List<String> GENDER_MARKERS = List.of("мужчина", "женщина");

    /**
     * Города, которые встречаются в выгрузках. Нужны только для того, чтобы
     * вынести город из строки компании («Центр метрологии, Краснодар»).
     * Список намеренно короткий: неизвестный город просто останется в компании,
     * а не потеряется.
     */
    private static final Set<String> KNOWN_CITIES = Set.of(
            "москва", "санкт-петербург", "петербург", "новосибирск", "екатеринбург", "казань",
            "нижний новгород", "челябинск", "самара", "омск", "ростов-на-дону", "уфа",
            "красноярск", "воронеж", "пермь", "волгоград", "краснодар", "саратов", "тюмень",
            "тольятти", "ижевск", "барнаул", "ульяновск", "иркутск", "хабаровск", "ярославль",
            "владивосток", "махачкала", "томск", "оренбург", "кемерово", "новокузнецк",
            "рязань", "астрахань", "пенза", "липецк", "киров", "чебоксары", "тула",
            "калининград", "курск", "сочи", "ставрополь", "белгород", "сургут", "владимир");

    /**
     * Разбор текста резюме.
     *
     * @param text     извлечённый текст (любой поддерживаемый формат)
     * @param fileName имя файла — только для сообщений об ошибке
     */
    public ParsedResume parse(String text, String fileName) {
        List<String> lines = prepareLines(text);
        if (lines.isEmpty()) {
            throw new ResumeFormatException("Из файла не удалось извлечь текст: " + fileName);
        }
        if (!looksLikeHhResume(lines)) {
            throw new ResumeFormatException(
                    "Формат резюме не поддерживается: ожидается выгрузка hh.ru "
                            + "(маркер «Желаемая должность и зарплата»). Файл: " + fileName);
        }

        Builder builder = new Builder();
        parseHeader(lines, builder);
        parsePosition(lines, builder);
        builder.experience.addAll(parseExperience(lines));
        builder.education.addAll(parseEducation(lines));
        builder.skills.addAll(parseSkills(lines));

        ParsedResume partial = builder.toPartial();
        List<String> missing = partial.missingRequiredFields();
        if (!missing.isEmpty()) {
            throw new ResumeFormatException(
                    "Резюме не соответствует требованиям, не найдены разделы: "
                            + String.join(", ", missing) + ". Файл: " + fileName);
        }
        return new ParsedResume(partial.name(), partial.gender(), partial.title(),
                partial.experience(), partial.education(), partial.skills(),
                CanonicalText.render(partial));
    }

    private List<String> prepareLines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : TextNormalizer.toLines(text)) {
            if (!isFooterArtifact(line)) {
                result.add(line);
            }
        }
        return result;
    }

    /** Колонтитул PDF-выгрузки: «Имя  •  Резюме обновлено …». */
    private boolean isFooterArtifact(String line) {
        return line.contains("\u2022") && DOC_UPDATED.matcher(line).find();
    }

    private boolean looksLikeHhResume(List<String> lines) {
        for (String line : lines) {
            if (ResumeLabels.startsWithHeader(line, ResumeLabels.DESIRED_POSITION)
                    || EXPERIENCE_TOTAL.matcher(line).matches()
                    || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_LOCATION) != null) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Шапка: имя, пол и контакты (контакты нужны только чтобы не спутать
    // их с именем кандидата)
    // ─────────────────────────────────────────────────────────────

    private void parseHeader(List<String> lines, Builder builder) {
        for (String line : lines) {
            if (DOC_UPDATED.matcher(line).find()) {
                continue;
            }
            if (isContactLine(line) || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_LOCATION) != null
                    || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_CITIZENSHIP) != null
                    || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_TRAVEL_TIME) != null) {
                continue;
            }
            String gender = genderMarker(line);
            if (gender != null) {
                builder.gender = gender;
                continue;
            }
            if (builder.name == null && isNameCandidate(line)) {
                builder.name = line;
            }
        }
    }

    private String genderMarker(String line) {
        String normalized = line.toLowerCase(Locale.ROOT).trim();
        for (String marker : GENDER_MARKERS) {
            if (normalized.equals(marker)) {
                return Character.toUpperCase(marker.charAt(0)) + marker.substring(1);
            }
        }
        return null;
    }

    private boolean isContactLine(String line) {
        return PHONE.matcher(line).find()
                || EMAIL.matcher(line).find()
                || MESSENGER.matcher(line).find();
    }

    /** Имя кандидата: 2–4 слова без цифр и служебных знаков. */
    private boolean isNameCandidate(String line) {
        if (line.length() > 60 || line.matches(".*\\d.*")) {
            return false;
        }
        if (line.contains(":") || line.contains("\u2022") || line.contains(",")) {
            return false;
        }
        if (!line.matches("(?u)[\\p{L}][\\p{L}\\s.\\-]*")) {
            return false;
        }
        int words = line.split("\\s+").length;
        return words >= 2 && words <= 4;
    }

    // ─────────────────────────────────────────────────────────────
    // Желаемая должность
    // ─────────────────────────────────────────────────────────────

    private void parsePosition(List<String> lines, Builder builder) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!ResumeLabels.startsWithHeader(line, ResumeLabels.DESIRED_POSITION)) {
                continue;
            }
            String glued = gluedValue(line, ResumeLabels.DESIRED_POSITION);
            if (glued != null) {
                builder.title = glued;
                return;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String candidate = lines.get(j);
                if (isTitleCandidate(candidate)) {
                    builder.title = candidate;
                    return;
                }
            }
            return;
        }
    }

    private String gluedValue(String line, String header) {
        String normalized = ResumeLabels.normalizeHeader(line);
        if (normalized.equals(header)) {
            return null;
        }
        int headerLength = Math.min(line.length(), header.length());
        String remainder = line.substring(headerLength).trim();
        return remainder.isEmpty() ? null : remainder;
    }

    private boolean isTitleCandidate(String line) {
        if (line.length() > 160 || line.contains("\u2022") || line.contains(":")) {
            return false;
        }
        return !isKeyValueLine(line) && !isSectionBoundary(line);
    }

    // ─────────────────────────────────────────────────────────────
    // Опыт работы
    // ─────────────────────────────────────────────────────────────

    private List<ExperienceEntry> parseExperience(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (EXPERIENCE_TOTAL.matcher(lines.get(i)).matches()) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        List<String> block = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            if (ResumeLabels.normalizeHeader(lines.get(i)).equals(ResumeLabels.EDUCATION)) {
                break;
            }
            block.add(lines.get(i));
        }
        return splitExperience(block);
    }

    /**
     * Нарезка блока опыта на записи.
     *
     * <p>Границы задают строки с диапазоном дат. В txt/rtf компания и город
     * стоят перед диапазоном, в PDF — после него, поэтому они собираются с
     * обеих сторон и привязываются к ближайшей записи.
     */
    private List<ExperienceEntry> splitExperience(List<String> block) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < block.size(); i++) {
            String line = block.get(i);
            if (DATE_RANGE.matcher(line).find() || (endsWithDash(line) && hasMonthBelow(block, i))) {
                starts.add(i);
            }
        }
        if (starts.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<String>> companyBefore = new LinkedHashMap<>();
        for (int startIndex : starts) {
            List<String> parts = new ArrayList<>();
            for (int i = startIndex - 1; i >= 0; i--) {
                String line = block.get(i);
                if (isCompanyLine(line)) {
                    parts.add(0, line);
                } else {
                    break;
                }
            }
            companyBefore.put(startIndex, parts);
        }

        List<ExperienceEntry> entries = new ArrayList<>();
        for (int d = 0; d < starts.size(); d++) {
            int startIndex = starts.get(d);
            int limit = d + 1 < starts.size() ? starts.get(d + 1) : block.size();

            String period = null;
            String position = null;
            List<String> companyParts = new ArrayList<>(companyBefore.get(startIndex));
            StringBuilder description = new StringBuilder();
            boolean descriptionStarted = false;
            boolean periodClosed = false;

            for (int i = startIndex; i < limit; i++) {
                String line = block.get(i);
                if (i == startIndex) {
                    period = line;
                    periodClosed = DATE_RANGE.matcher(line).find();
                    continue;
                }
                if (!periodClosed && startsWithMonth(line)) {
                    // В PDF точка переноса может добавить второе тире: «— - Декабрь»
                    period = squashDashes((period == null ? "" : period + " ") + line);
                    periodClosed = true;
                    continue;
                }
                // Длительность не является ни компанией, ни началом описания
                if (DURATION.matcher(line).matches()) {
                    continue;
                }
                if (!descriptionStarted && isCompanyLine(line)) {
                    if (position == null && looksLikePosition(line)) {
                        position = line;
                    } else {
                        companyParts.add(line);
                    }
                    continue;
                }
                descriptionStarted = true;
                description.append(line).append('\n');
            }

            if (position == null && !companyParts.isEmpty()) {
                position = companyParts.removeFirst();
            }
            String descriptionText = description.toString().trim();
            boolean empty = position == null && companyParts.isEmpty() && descriptionText.isEmpty();
            if (empty) {
                continue;
            }
            // В выгрузке компания стоит первой строкой, город — второй либо
            // приписан к компании через запятую («Центр метрологии, Краснодар»).
            String city = companyParts.size() >= 2 ? companyParts.removeLast() : null;
            String company = companyParts.isEmpty() ? null : String.join(", ", companyParts);
            int lastComma = company == null ? -1 : company.lastIndexOf(',');
            if (lastComma > 0) {
                String tail = company.substring(lastComma + 1).trim();
                if (looksLikeCity(tail)) {
                    city = city == null ? tail : city;
                    company = company.substring(0, lastComma).trim();
                }
            }
            entries.add(new ExperienceEntry(
                    period,
                    company,
                    city,
                    position,
                    descriptionText.isEmpty() ? null : descriptionText));
        }
        return entries;
    }

    /** Ниже есть строка-месяц, закрывающая разорванный диапазон дат. */
    private boolean hasMonthBelow(List<String> block, int fromIndex) {
        for (int i = fromIndex + 1; i < block.size(); i++) {
            String line = block.get(i);
            if (startsWithMonth(line)) {
                return true;
            }
            if (endsWithDash(line)) {
                return false;
            }
            if (!isCompanyLine(line) && !DURATION.matcher(line).matches()) {
                return false;
            }
        }
        return false;
    }

    private boolean endsWithDash(String line) {
        if (line.isEmpty()) {
            return false;
        }
        char last = line.charAt(line.length() - 1);
        return last == '\u2014' || last == '\u2013' || last == '-';
    }

    private boolean startsWithMonth(String line) {
        return MONTH_LINE.matcher(line).matches();
    }

    /** Приводит повторные тире к одному: PDF добавляет лишнее на переносе строки. */
    private String squashDashes(String value) {
        return value.replaceAll("\\s*[\u2014\u2013-]\\s*[\u2014\u2013-]\\s*", " \u2014 ");
    }

    /** Строка может быть компанией или городом. */
    private boolean isCompanyLine(String line) {
        if (line.endsWith(":") || line.startsWith("-") || line.startsWith("\u2014") || line.endsWith(".")) {
            return false;
        }
        if (DURATION.matcher(line).matches() || DATE_RANGE.matcher(line).find()) {
            return false;
        }
        return line.length() <= 80;
    }

    private boolean looksLikePosition(String line) {
        String normalized = line.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String token : normalized.split("[^\\p{L}]+")) {
            for (String role : ROLE_KEYWORDS) {
                if (token.startsWith(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Город: отдельное слово без цифр, встречающееся в выгрузках как место работы. */
    private boolean looksLikeCity(String line) {
        String candidate = line.trim();
        if (candidate.isEmpty() || candidate.length() > 30 || candidate.contains(":")) {
            return false;
        }
        if (candidate.matches(".*\\d.*")) {
            return false;
        }
        return KNOWN_CITIES.contains(candidate.toLowerCase(Locale.ROOT));
    }

    // ─────────────────────────────────────────────────────────────
    // Образование
    // ─────────────────────────────────────────────────────────────

    private List<EducationEntry> parseEducation(List<String> lines) {
        int start = indexOfHeader(lines, ResumeLabels.EDUCATION);
        if (start < 0) {
            return List.of();
        }
        List<EducationEntry> entries = new ArrayList<>();
        String year = null;
        String institution = null;
        String specialty = null;

        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > start && isEducationTerminator(line)) {
                break;
            }
            if (i == start) {
                String glued = gluedValue(line, ResumeLabels.EDUCATION);
                if (glued != null && !glued.isBlank()) {
                    institution = glued;
                }
                continue;
            }
            if (GRADUATION_YEAR.matcher(line).matches()) {
                year = line;
                continue;
            }
            if (isDegreeMarker(line)) {
                continue;
            }
            if (looksLikeInstitution(line)) {
                if (institution != null) {
                    entries.add(new EducationEntry(year, institution, specialty));
                    year = null;
                    specialty = null;
                }
                institution = line;
                continue;
            }
            specialty = specialty == null ? line : specialty + ", " + line;
        }
        if (institution != null || specialty != null) {
            entries.add(new EducationEntry(year, institution, specialty));
        }
        return entries;
    }

    private boolean isEducationTerminator(String line) {
        String normalized = ResumeLabels.normalizeHeader(line);
        return normalized.equals(ResumeLabels.ADDITIONAL_INFO)
                || normalized.equals(ResumeLabels.LANGUAGES)
                || normalized.equals(ResumeLabels.SKILLS)
                || normalized.endsWith(" " + ResumeLabels.SKILLS);
    }

    private boolean isDegreeMarker(String line) {
        if (DEGREE_MARKERS.contains(line.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return line.toLowerCase(Locale.ROOT).matches(".*\\((высшее|бакалавр|магистр|специалист)\\).*");
    }

    private boolean looksLikeInstitution(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String keyword : INSTITUTION_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Навыки
    // ─────────────────────────────────────────────────────────────

    /**
     * Навыки берутся после <b>последнего</b> вхождения «Навыки»: в выгрузке это
     * слово встречается дважды — как заголовок раздела и как заголовок списка.
     */
    private List<String> parseSkills(List<String> lines) {
        int start = lastIndexOfHeader(lines, ResumeLabels.SKILLS);
        if (start < 0) {
            return List.of();
        }
        List<String> skills = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == start) {
                String glued = gluedValue(line, ResumeLabels.SKILLS);
                if (glued != null) {
                    addSkills(glued, skills);
                }
                continue;
            }
            if (ResumeLabels.normalizeHeader(line).equals(ResumeLabels.ADDITIONAL_INFO)
                    || ResumeLabels.normalizeHeader(line).equals(ResumeLabels.ABOUT)) {
                break;
            }
            addSkills(line, skills);
        }
        return skills;
    }

    private void addSkills(String value, List<String> skills) {
        String cleaned = value.trim();
        if (cleaned.isEmpty() || cleaned.contains("Навыки")
                || ResumeLabels.normalizeHeader(cleaned).equals(ResumeLabels.LANGUAGES)) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        if (cleaned.contains(";")) {
            candidates.addAll(List.of(cleaned.split(";")));
        } else if (containsWideGap(cleaned)) {
            candidates.addAll(List.of(cleaned.split("\\s{2,}")));
        } else {
            candidates.addAll(List.of(cleaned.split("\\s+")));
        }
        for (String candidate : candidates) {
            String skill = candidate.trim();
            if (!skill.isEmpty() && skill.length() <= 60 && !skills.contains(skill)) {
                skills.add(skill);
            }
        }
    }

    private boolean containsWideGap(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == ' ' && value.charAt(i - 1) == ' ') {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Общие предикаты и утилиты
    // ─────────────────────────────────────────────────────────────

    private boolean isKeyValueLine(String line) {
        return ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_LOCATION) != null
                || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_CITIZENSHIP) != null
                || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_EMPLOYMENT_TYPE) != null
                || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_WORK_FORMAT) != null
                || ResumeLabels.valueAfterKey(line, ResumeLabels.KEY_TRAVEL_TIME) != null;
    }

    private boolean isSectionBoundary(String line) {
        String normalized = ResumeLabels.normalizeHeader(line);
        return normalized.equals(ResumeLabels.EDUCATION)
                || normalized.equals(ResumeLabels.SKILLS)
                || normalized.equals(ResumeLabels.LANGUAGES)
                || normalized.equals(ResumeLabels.ADDITIONAL_INFO)
                || normalized.equals(ResumeLabels.ABOUT)
                || normalized.equals(ResumeLabels.SPECIALIZATIONS)
                || EXPERIENCE_TOTAL.matcher(line).matches();
    }

    private int indexOfHeader(List<String> lines, String header) {
        for (int i = 0; i < lines.size(); i++) {
            if (ResumeLabels.startsWithHeader(lines.get(i), header)) {
                return i;
            }
        }
        return -1;
    }

    private int lastIndexOfHeader(List<String> lines, String header) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (ResumeLabels.startsWithHeader(lines.get(i), header)) {
                return i;
            }
        }
        return -1;
    }

    /** Накопитель распарсенных полей. */
    private static final class Builder {
        private String name;
        private String gender;
        private String title;
        private final List<ExperienceEntry> experience = new ArrayList<>();
        private final List<EducationEntry> education = new ArrayList<>();
        private final List<String> skills = new ArrayList<>();

        private ParsedResume toPartial() {
            return new ParsedResume(name, gender, title, experience, education, skills, "");
        }
    }
}