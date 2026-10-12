package com.github.reactivity512.rag.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Генерация бинарных версий golden-корпуса (docx/pdf) из текстовых образцов.
 *
 * <p>Запускается только по требованию:
 * {@code mvn test -Dtest=FixtureGeneratorTest -Drag.generate-fixtures=true}.
 * Результат коммитится в {@code resumes/samples}, чтобы проверка
 * кросс-форматной согласованности работала без генерации на лету.
 */
@EnabledIfSystemProperty(named = "rag.generate-fixtures", matches = "true")
class FixtureGeneratorTest {

    private static final Path SAMPLES = Path.of("..", "resumes", "samples");

    @Test
    void generateDocxAndPdfFromSamples() throws IOException {
        List<Path> textFiles;
        try (var stream = Files.list(SAMPLES)) {
            textFiles = stream.filter(path -> path.toString().endsWith(".txt")).sorted().toList();
        }
        for (Path txt : textFiles) {
            String content = Files.readString(txt, StandardCharsets.UTF_8);
            String base = txt.getFileName().toString().replaceAll("\\.txt$", "");

            Path docx = SAMPLES.resolve(base + ".docx");
            Files.write(docx, FixtureFactory.docxFromText(content));

            Path pdf = SAMPLES.resolve(base + ".pdf");
            Files.write(pdf, FixtureFactory.pdfFromText(content));

            System.out.println("[GEN] " + base + " -> docx " + Files.size(docx)
                    + " байт, pdf " + Files.size(pdf) + " байт");
        }
    }
}
