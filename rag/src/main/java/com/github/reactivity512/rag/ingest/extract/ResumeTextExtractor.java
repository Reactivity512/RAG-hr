package com.github.reactivity512.rag.ingest.extract;

import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.reactivity512.rag.ingest.ResumeFormatException;
import com.github.reactivity512.rag.ingest.SourceFormat;

/**
 * Извлечение plain text из поддерживаемых форматов файлов.
 *
 * <p>Поддерживаются: {@code txt}, {@code pdf}, {@code docx}, {@code rtf}.
 * Формат определяется по содержимому, а не по расширению.
 *
 * <p>RTF разбирается собственным {@link RtfTextExtractor}: POI RTF не умеет.
 * Бинарный Word 97-2003 ({@code .doc}, OLE2) намеренно не поддерживается:
 * в тестовом наборе проекта такого файла нет, а сам формат вытеснен.
 */
@Component
public class ResumeTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeTextExtractor.class);

    private final RtfTextExtractor rtfTextExtractor;

    public ResumeTextExtractor(RtfTextExtractor rtfTextExtractor) {
        this.rtfTextExtractor = rtfTextExtractor;
    }

    /** Текст из файла указанного формата. */
    public String extract(byte[] content, String fileName, SourceFormat format) {
        if (content == null || content.length == 0) {
            throw new ResumeFormatException("Файл резюме пустой: " + fileName);
        }
        return switch (format) {
            case TXT -> decodeUtf8(content, fileName);
            case PDF -> extractPdf(content, fileName);
            case DOCX -> extractDocx(content, fileName);
            case RTF -> extractRtf(content, fileName);
        };
    }

    private String decodeUtf8(byte[] content, String fileName) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new ResumeFormatException(
                    "Файл " + fileName + " не является текстом в кодировке UTF-8", e);
        }
    }

    private String extractPdf(byte[] content, String fileName) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new ResumeFormatException("PDF защищён паролем: " + fileName);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.debug("PDF {}: страниц {}, извлечено {} символов",
                    fileName, document.getNumberOfPages(), text.length());
            return text;
        } catch (ResumeFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new ResumeFormatException("Не удалось прочитать PDF: " + fileName, e);
        }
    }

    private String extractDocx(byte[] content, String fileName) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (Exception e) {
            throw new ResumeFormatException("Не удалось прочитать DOCX: " + fileName, e);
        }
    }

    private String extractRtf(byte[] content, String fileName) {
        try {
            String text = rtfTextExtractor.extract(content);
            log.debug("RTF {}: извлечено {} символов", fileName, text.length());
            return text;
        } catch (Exception e) {
            throw new ResumeFormatException("Не удалось прочитать RTF: " + fileName, e);
        }
    }
}
