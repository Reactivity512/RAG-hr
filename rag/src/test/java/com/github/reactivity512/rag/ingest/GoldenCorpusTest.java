package com.github.reactivity512.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.github.reactivity512.rag.ingest.domain.ParsedResume;
import com.github.reactivity512.rag.ingest.extract.FormatDetector;
import com.github.reactivity512.rag.ingest.extract.ResumeTextExtractor;
import com.github.reactivity512.rag.ingest.extract.RtfTextExtractor;
import com.github.reactivity512.rag.ingest.parse.ResumeParser;

/**
 * Golden-корпус: каталог {@code resumes/samples}.
 *
 * <p>Для каждого образца лежат три представления одного и того же резюме —
 * txt, docx и pdf. Тест проверяет две вещи:
 * <ol>
 *   <li>каждый файл разбирается и даёт обязательные поля (должность, опыт, навыки);
 *   <li>все форматы одного образца дают одинаковый результат разбора и
 *       одинаковый текст для эмбеддинга — это ключевое требование, потому что
 *       иначе одно резюме в разных форматах даст разные векторы.
 * </ol>
 */
class GoldenCorpusTest {

    private static final Path SAMPLES = Path.of("..", "resumes", "samples");

    private final ResumeParser parser = new ResumeParser();
    private final ResumeTextExtractor extractor = new ResumeTextExtractor(new RtfTextExtractor());

    @Test
    @DisplayName("каждый файл корпуса разбирается и содержит обязательные разделы")
    void everyCorpusFileIsParsed() throws IOException {
        List<Path> files = corpusFiles();
        assertThat(files).as("в корпусе должны быть файлы").isNotEmpty();

        List<String> parsed = new ArrayList<>();
        for (Path file : files) {
            ParsedResume resume = parse(file);
            assertThat(resume.title()).as("должность в %s", file.getFileName()).isNotBlank();
            assertThat(resume.experience()).as("опыт в %s", file.getFileName()).isNotEmpty();
            assertThat(resume.skills()).as("навыки в %s", file.getFileName()).isNotEmpty();
            assertThat(resume.fullText()).as("текст для эмбеддинга в %s", file.getFileName()).isNotBlank();
            parsed.add(file.getFileName() + ": " + resume.title() + " / "
                    + resume.experience().size() + " мест / " + resume.skills().size() + " навыков");
        }
        parsed.forEach(line -> System.out.println("[CORPUS] " + line));
    }

    @Test
    @DisplayName("txt, docx и pdf одного образца дают одинаковый разбор и одинаковый текст")
    void formatsOfSameSampleAgree() throws IOException {
        for (String base : sampleBases()) {
            ParsedResume fromTxt = parse(SAMPLES.resolve(base + ".txt"));
            ParsedResume fromDocx = parse(SAMPLES.resolve(base + ".docx"));
            ParsedResume fromPdf = parse(SAMPLES.resolve(base + ".pdf"));

            assertThat(fromDocx.experience()).as("опыт docx %s", base).isEqualTo(fromTxt.experience());
            assertThat(fromPdf.experience()).as("опыт pdf %s", base).isEqualTo(fromTxt.experience());
            assertThat(fromDocx.skills()).as("навыки docx %s", base).isEqualTo(fromTxt.skills());
            assertThat(fromPdf.skills()).as("навыки pdf %s", base).isEqualTo(fromTxt.skills());
            assertThat(fromDocx.education()).as("образование docx %s", base).isEqualTo(fromTxt.education());
            assertThat(fromPdf.education()).as("образование pdf %s", base).isEqualTo(fromTxt.education());
            assertThat(fromDocx.title()).as("должность docx %s", base).isEqualTo(fromTxt.title());
            assertThat(fromPdf.title()).as("должность pdf %s", base).isEqualTo(fromTxt.title());

            // Главное: текст для эмбеддинга не зависит от формата файла
            assertThat(fromDocx.fullText()).as("текст docx %s", base).isEqualTo(fromTxt.fullText());
            assertThat(fromPdf.fullText()).as("текст pdf %s", base).isEqualTo(fromTxt.fullText());
            System.out.println("[CORPUS] " + base + ": форматы согласованы, текст "
                    + fromTxt.fullText().length() + " символов");
        }
    }

    @Test
    @DisplayName("навыки не смешиваются с разделом языков, город вынесен отдельно")
    void corpusEdgeCases() throws IOException {
        ParsedResume qa = parse(SAMPLES.resolve("sample-qa-no-company.txt"));
        assertThat(qa.skills()).doesNotContain("Знание языков", "Русский — Родной");
        assertThat(qa.skills()).contains("Selenium", "Postman", "JUnit");

        ParsedResume java = parse(SAMPLES.resolve("sample-java-moscow-vs-spb.txt"));
        assertThat(java.experience()).hasSize(2);
        assertThat(java.experience().get(0).company()).isEqualTo("Яндекс");
        assertThat(java.experience().get(0).city()).isEqualTo("Санкт-Петербург");
        assertThat(java.experience().get(1).company()).isEqualTo("ООО Ромашка");
    }

    private ParsedResume parse(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        SourceFormat format = FormatDetector.detect(content);
        assertThat(format).as("формат файла %s", name).isNotNull();
        String text = extractor.extract(content, name, format);
        return parser.parse(text, name);
    }

    private List<Path> corpusFiles() throws IOException {
        try (var stream = Files.list(SAMPLES)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    /** Базовые имена образцов, у которых есть все три формата. */
    private List<String> sampleBases() throws IOException {
        List<String> bases = new ArrayList<>();
        try (var stream = Files.list(SAMPLES)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".txt")) {
                    continue;
                }
                String base = name.substring(0, name.length() - 4);
                if (Files.exists(SAMPLES.resolve(base + ".docx")) && Files.exists(SAMPLES.resolve(base + ".pdf"))) {
                    bases.add(base);
                }
            }
        }
        return bases.stream().sorted().toList();
    }

    /** Текстовое представление корпуса — для отладки при падении теста. */
    @SuppressWarnings("unused")
    private String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
