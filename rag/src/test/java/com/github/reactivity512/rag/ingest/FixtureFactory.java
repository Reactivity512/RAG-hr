package com.github.reactivity512.rag.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * Генерация бинарных представлений одного и того же резюме.
 *
 * <p>Нужна, чтобы проверить главное требование: результат парсинга не зависит
 * от формата файла. PDF и DOCX собираются в тесте из текстовой фикстуры,
 * поэтому бинарные артефакты в репозитории не хранятся (кроме реальной
 * выгрузки hh.ru, которую нельзя воспроизвести синтетически).
 */
public final class FixtureFactory {

    /** Шрифт с кириллицей; в CI может отсутствовать — тест в этом случае пропускается. */
    private static final Path CYRILLIC_FONT = Path.of("C:/Windows/Fonts/arial.ttf");

    private FixtureFactory() {
    }

    public static boolean cyrillicFontAvailable() {
        return Files.isRegularFile(CYRILLIC_FONT);
    }

    /** Текстовая фикстура из тестовых ресурсов. */
    public static String readTextFixture(String resourcePath) {
        try (InputStream in = FixtureFactory.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Фикстура не найдена: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] readBinaryFixture(String resourcePath) {
        try (InputStream in = FixtureFactory.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Фикстура не найдена: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * PDF, отрисованный из текста построчно.
     *
     * <p>Шрифт и ширина страницы подобраны так, чтобы строки фикстуры не
     * переносились: иначе тест проверял бы переносы, а не согласованность
     * разбора. Реальные выгрузки с переносами покрывает отдельная фикстура
     * {@code real-export.pdf}.
     */
    public static byte[] pdfFromText(String text) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, CYRILLIC_FONT.toFile());
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(1400f, 2000f));
            document.addPage(page);

            float margin = 20f;
            float fontSize = 7f;
            float leading = 9f;
            float y = page.getMediaBox().getHeight() - margin;

            PDPageContentStream stream = new PDPageContentStream(document, page);
            for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    y -= leading;
                    continue;
                }
                if (y < margin) {
                    stream.close();
                    page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(1400f, 2000f));
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    y = page.getMediaBox().getHeight() - margin;
                }
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(margin, y);
                stream.showText(line);
                stream.endText();
                y -= leading;
            }
            stream.close();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** DOCX: один абзац на строку фикстуры. */
    public static byte[] docxFromText(String text) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.setText(raw.trim());
            }
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * RTF из того же текста.
     *
     * <p>Кириллица записывается через Unicode-escape, тире — управляющим словом
     * {@code emdash}, пробелы — экранированным пробелом: именно так выглядит
     * реальная выгрузка hh.ru, сохранённая как RTF. Плюс встроенная «картинка»
     * и колонтитул, которые не должны попасть в текст.
     */
    public static byte[] rtfFromText(String text) {
        StringBuilder rtf = new StringBuilder();
        rtf.append("{\\rtf1\\ansi\\ansicpg1251\\deff0")
                .append("{\\fonttbl{\\f0\\fswiss Arial;}}")
                .append("{\\header{\\fs16 Резюме обновлено}}");
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            rtf.append("\\par ");
            appendRtfLine(rtf, raw.trim());
        }
        // Встроенная картинка и служебные группы не должны попасть в текст
        rtf.append("{\\*\\shppict{\\pict\\pngblip 89504e470d0a1a0a}}");
        rtf.append("}");
        return rtf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void appendRtfLine(StringBuilder rtf, String line) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            switch (ch) {
                case '\u2014' -> rtf.append("\\emdash ");
                case '\u2013' -> rtf.append("\\endash ");
                case ' ' -> rtf.append("\\~");
                case '{', '}', '\\' -> rtf.append('\\').append(ch);
                default -> {
                    if (ch < 128) {
                        rtf.append(ch);
                    } else {
                        rtf.append("\\u").append((int) ch).append('?');
                    }
                }
            }
        }
    }
}
