package com.github.reactivity512.rag.ingest;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.reactivity512.rag.ingest.domain.EducationEntry;
import com.github.reactivity512.rag.ingest.domain.ExperienceEntry;
import com.github.reactivity512.rag.ingest.domain.ParsedResume;
import com.github.reactivity512.rag.ingest.extract.FormatDetector;
import com.github.reactivity512.rag.ingest.extract.ResumeTextExtractor;
import com.github.reactivity512.rag.ingest.extract.RtfTextExtractor;
import com.github.reactivity512.rag.ingest.parse.ResumeParser;

/**
 * Тесты парсера hh.ru-выгрузки и извлечения текста.
 *
 * <p>Инфраструктура (Qdrant, Redis, Ollama) не нужна: тесты работают только
 * с файлами и кодом разбора. Эталон берётся из golden-корпуса
 * {@code resumes/samples}.
 */
class ResumeParserTest {

    private static final Path SAMPLES = Path.of("..", "resumes", "samples");
    private static final Path REFERENCE = SAMPLES.resolve("sample-backend-golang.txt");

    private final ResumeParser parser = new ResumeParser();
    private final RtfTextExtractor rtfExtractor = new RtfTextExtractor();
    private final ResumeTextExtractor textExtractor = new ResumeTextExtractor(rtfExtractor);

    private String referenceText() throws Exception {
        return Files.readString(REFERENCE, StandardCharsets.UTF_8);
    }

    private ParsedResume parseReference() throws Exception {
        return parser.parse(referenceText(), REFERENCE.getFileName().toString());
    }

    // ─────────────────────────────────────────────────────────────
    // Поля эталонной выгрузки
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("имя, пол и должность извлекаются")
    void parsesIdentity() throws Exception {
        ParsedResume resume = parseReference();

        assertThat(resume.name()).isEqualTo("Иванов Иван");
        assertThat(resume.gender()).isEqualTo("Мужчина");
        assertThat(resume.title()).isEqualTo("Backend разработчик (Java, Go, LLM, RAG)");
    }

    @Test
    @DisplayName("опыт работы разбит на записи: компания, город, должность, период, описание")
    void parsesExperience() throws Exception {
        ParsedResume resume = parseReference();

        assertThat(resume.experience()).hasSize(3);

        ExperienceEntry first = resume.experience().get(0);
        assertThat(first.period()).isEqualTo("Июнь 2022 — Декабрь 2025 3 года 7 месяцев");
        assertThat(first.company()).isEqualTo("Компания");
        assertThat(first.city()).isEqualTo("Москва");
        assertThat(first.position()).isEqualTo("Backend-разработчик");
        assertThat(first.description()).contains("Golang").contains("Clean Architecture");

        // Во второй записи компании нет — это штатная ситуация выгрузки
        ExperienceEntry second = resume.experience().get(1);
        assertThat(second.period()).isEqualTo("Август 2020 — Июнь 2022 1 год 11 месяцев");
        assertThat(second.company()).isNull();
        assertThat(second.position()).isEqualTo("Backend-разработчик");

        ExperienceEntry third = resume.experience().get(2);
        assertThat(third.company()).isEqualTo("Компания2");
        assertThat(third.city()).isEqualTo("Москва");
        assertThat(third.position()).isEqualTo("Fullstack-разработчик");
    }

    @Test
    @DisplayName("порядок «компания до дат» и «компания после дат» разбирается одинаково")
    void parsesBothExperienceLayouts() {
        String companyBeforeDates = """
                Резюме обновлено 1 января 2026 в 10:00

                Иван Петров

                Проживает: Москва

                Желаемая должность и зарплата

                Java разработчик

                Опыт работы — 3 года

                Альфа

                Москва

                Январь 2021 — Декабрь 2024 3 года

                Ведущий разработчик

                Разработка сервисов на Java.

                Образование

                Высшее

                Навыки
                Java; Spring
                """;
        String companyAfterDates = """
                Резюме обновлено 1 января 2026 в 10:00

                Иван Петров

                Проживает: Москва

                Желаемая должность и зарплата

                Java разработчик

                Опыт работы — 3 года

                Январь 2021 —

                Декабрь 2024

                3 года

                Бета

                Ведущий разработчик

                Разработка сервисов на Java.

                Образование

                Высшее

                Навыки
                Java; Spring
                """;

        ExperienceEntry fromFirst = parser.parse(companyBeforeDates, "a.txt").experience().getFirst();
        ExperienceEntry fromSecond = parser.parse(companyAfterDates, "b.txt").experience().getFirst();

        assertThat(fromFirst.period()).isEqualTo("Январь 2021 — Декабрь 2024 3 года");
        assertThat(fromFirst.company()).isEqualTo("Альфа");
        assertThat(fromFirst.city()).isEqualTo("Москва");
        assertThat(fromFirst.position()).isEqualTo("Ведущий разработчик");

        // В PDF-выгрузке диапазон дат разорван, а компания идёт после него
        assertThat(fromSecond.period()).isEqualTo("Январь 2021 — Декабрь 2024");
        assertThat(fromSecond.company()).isEqualTo("Бета");
        assertThat(fromSecond.position()).isEqualTo("Ведущий разработчик");
    }

    @Test
    @DisplayName("education собирается из блока «Образование» с вузом, специальностью и годом")
    void parsesEducation() throws Exception {
        ParsedResume resume = parseReference();

        assertThat(resume.education()).isNotEmpty();
        EducationEntry first = resume.education().getFirst();
        assertThat(first.institution()).isEqualTo("Федеральный университет, Москва");
        assertThat(first.year()).isEqualTo("2013");
        assertThat(first.specialty()).contains("Прикладная информатика");
    }

    @Test
    @DisplayName("навыки берутся из нижнего раздела «Навыки», языки в них не попадают")
    void parsesSkills() throws Exception {
        ParsedResume resume = parseReference();

        assertThat(resume.skills())
                .contains("Docker", "Kubernetes", "Golang", "RabbitMQ", "Redis", "SQL", "Java", "RAG", "LLM");
        assertThat(resume.skills()).doesNotContain("Знание языков");
        assertThat(resume.skills()).noneMatch(skill -> skill.contains("Русский"));
    }

    // ─────────────────────────────────────────────────────────────
    // Обязательные поля и краевые случаи
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("без должности, опыта или навыков документ отвергается с перечнем разделов")
    void rejectsWhenRequiredFieldsMissing() {
        String withoutSkills = """
                Резюме обновлено 1 января 2026 в 10:00

                Иван Петров

                Желаемая должность и зарплата

                Java разработчик

                Опыт работы — 3 года

                Альфа

                Москва

                Январь 2021 — Декабрь 2024 3 года

                Ведущий разработчик

                Разработка сервисов на Java.

                Образование

                Высшее

                Сибирский федеральный университет, Красноярск
                2013
                """;

        assertThatThrownBy(() -> parser.parse(withoutSkills, "no-skills.txt"))
                .isInstanceOf(ResumeFormatException.class)
                .hasMessageContaining("Навыки");
    }

    @Test
    @DisplayName("старый шаблон проекта отвергается с понятной ошибкой")
    void rejectsLegacyTemplate() {
        String legacy = """
                Название специальности: Python разработчик

                Заработная плата: 65 000 руб/месяц

                Формат работы: Гибрид

                Контакты: alex.p@domain.com

                Опыт работы:
                IT Solutions, Python разработчик (2 года): Разработка.

                Навыки:
                Python (Продвинутый)
                """;

        assertThatThrownBy(() -> parser.parse(legacy, "legacy.txt"))
                .isInstanceOf(ResumeFormatException.class)
                .hasMessageContaining("Формат резюме не поддерживается");
    }

    @Test
    @DisplayName("пустой текст отвергается")
    void rejectsEmptyText() {
        assertThatThrownBy(() -> parser.parse("   \n\n  ", "empty.txt"))
                .isInstanceOf(ResumeFormatException.class)
                .hasMessageContaining("не удалось извлечь текст");
    }

    @Test
    @DisplayName("CRLF, BOM и отсутствие перевода строки в конце не ломают разбор")
    void handlesCrlfBomAndMissingTrailingNewline() throws Exception {
        String text = "\uFEFF" + referenceText().replace("\n", "\r\n").stripTrailing();

        ParsedResume resume = parser.parse(text, "crlf.txt");

        assertThat(resume.name()).isEqualTo("Иванов Иван");
        assertThat(resume.skills()).contains("Docker");
    }

    // ─────────────────────────────────────────────────────────────
    // Извлечение текста и определение формата
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("формат определяется по содержимому, а не по расширению")
    void detectsFormatByContentNotExtension() {
        byte[] rtf = FixtureFactory.rtfFromText("Тестовое резюме");
        byte[] pdf = FixtureFactory.pdfFromText("Тестовое резюме");
        byte[] docx = FixtureFactory.docxFromText("Тестовое резюме");

        assertThat(FormatDetector.detect(rtf)).isEqualTo(SourceFormat.RTF);
        assertThat(FormatDetector.detect(pdf)).isEqualTo(SourceFormat.PDF);
        assertThat(FormatDetector.detect(docx)).isEqualTo(SourceFormat.DOCX);
        assertThat(FormatDetector.detect("обычный текст".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(SourceFormat.TXT);
        // Именно так пришёл файл «пример нового резюме.doc»: RTF под именем .doc
        assertThat(SourceFormat.fromFileName("пример нового резюме.doc")).isNull();
    }

    @Test
    @DisplayName("RTF читается: кириллица, тире, картинки и колонтитулы")
    void extractsTextFromRtf() throws Exception {
        assumeTrue(FixtureFactory.cyrillicFontAvailable(), "нет шрифта с кириллицей");
        byte[] rtf = FixtureFactory.rtfFromText(referenceText());

        String text = textExtractor.extract(rtf, "sample.rtf", SourceFormat.RTF);

        assertThat(text).contains("Иванов Иван");
        assertThat(text).contains("Backend разработчик");
        assertThat(text).contains("Июнь 2022 — Декабрь 2025");
        assertThat(text).contains("Компания");
        // Бинарные данные встроенного изображения не должны попасть в текст
        assertThat(text).doesNotContain("89504e470d0a1a0a");
    }

    @Test
    @DisplayName("PDF читается, колонтитул не мешает")
    void extractsTextFromPdf() throws Exception {
        assumeTrue(FixtureFactory.cyrillicFontAvailable(), "нет шрифта с кириллицей");
        byte[] pdf = FixtureFactory.pdfFromText(referenceText());

        String text = textExtractor.extract(pdf, "sample.pdf", SourceFormat.PDF);

        assertThat(text).contains("Желаемая должность и зарплата");
        assertThat(text).contains("Опыт работы");
    }

    @Test
    @DisplayName("неподдерживаемый формат не определяется как резюме")
    void rejectsUnknownFormat() {
        assertThat(FormatDetector.detect(new byte[]{0x00, 0x01, 0x02, 0x03, 0x00})).isNull();
    }

    @Test
    @DisplayName("не-UTF-8 текстовый файл отвергается")
    void rejectsNonUtf8Text() {
        byte[] cp1251 = "Резюме".getBytes(Charset.forName("windows-1251"));

        assertThatThrownBy(() -> textExtractor.extract(cp1251, "cp1251.txt", SourceFormat.TXT))
                .isInstanceOf(ResumeFormatException.class)
                .hasMessageContaining("UTF-8");
    }

    // ─────────────────────────────────────────────────────────────
    // Кросс-форматная согласованность
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PDF и DOCX из того же текста дают тот же разбор и тот же текст для эмбеддинга")
    void generatedFormatsMatchText() throws Exception {
        assumeTrue(FixtureFactory.cyrillicFontAvailable(), "нет шрифта с кириллицей");
        String text = referenceText();
        ParsedResume fromText = parser.parse(text, "sample.txt");

        byte[] pdf = FixtureFactory.pdfFromText(text);
        ParsedResume fromPdf = parser.parse(
                textExtractor.extract(pdf, "sample.pdf", SourceFormat.PDF), "sample.pdf");

        byte[] docx = FixtureFactory.docxFromText(text);
        ParsedResume fromDocx = parser.parse(
                textExtractor.extract(docx, "sample.docx", SourceFormat.DOCX), "sample.docx");

        assertThat(fromPdf.experience()).isEqualTo(fromText.experience());
        assertThat(fromDocx.experience()).isEqualTo(fromText.experience());
        assertThat(fromPdf.skills()).isEqualTo(fromText.skills());
        assertThat(fromDocx.skills()).isEqualTo(fromText.skills());
        assertThat(fromPdf.education()).isEqualTo(fromText.education());
        // Главное: текст для эмбеддинга не зависит от формата файла
        assertThat(fromPdf.fullText()).isEqualTo(fromText.fullText());
        assertThat(fromDocx.fullText()).isEqualTo(fromText.fullText());
    }
}
