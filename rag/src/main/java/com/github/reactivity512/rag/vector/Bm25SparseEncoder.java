package com.github.reactivity512.rag.vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.SparseVector;
import io.qdrant.client.grpc.Points.VectorInput;
import io.qdrant.client.grpc.Points.SparseIndices;
import io.qdrant.client.grpc.Points.Vector;

/**
 * Лексический (sparse) вектор по формуле Okapi BM25 — считается на стороне Java.
 *
 * <p>Почему не серверный {@code qdrant/bm25}: Qdrant OSS для генерации
 * документов по тексту требует отдельный inference-сервис
 * ({@code INFERENCE__URL}), а договорённость — держать инфраструктуру из
 * Qdrant и Redis. Поэтому вектор считается здесь, а Qdrant по-прежнему
 * применяет IDF через {@code modifier: idf} в конфигурации sparse-вектора.
 *
 * <p>Что это значит по числам: в вектор попадает насыщенная частота терма с
 * нормализацией по длине документа (часть формулы BM25), а IDF остаётся за
 * Qdrant. Такой порядок — тот же компромисс, что и в FastEmbed: серверный IDF
 * пересчитывается по всей коллекции, поэтому окончательные скоры остаются
 * сопоставимыми между документами.
 *
 * <p>Параметры BM25 совпадают с дефолтами Qdrant: {@code k1 = 1.2},
 * {@code b = 0.75}, {@code avg_len = 256} слов.
 */
@Component
public class Bm25SparseEncoder {

    /** Дефолты Qdrant для BM25. */
    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;
    public static final double DEFAULT_AVG_LEN = 256.0;

    /** Токен: буквы и цифры, включая кириллицу. */
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_TOKEN_LENGTH = 40;

    private final double k1;
    private final double b;
    private final double avgLength;

    public Bm25SparseEncoder() {
        this(DEFAULT_K1, DEFAULT_B, DEFAULT_AVG_LEN);
    }

    public Bm25SparseEncoder(double k1, double b, double avgLength) {
        this.k1 = k1;
        this.b = b;
        this.avgLength = avgLength;
    }

    /** Разбивает текст на нормализованные токены. */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() <= MAX_TOKEN_LENGTH) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Sparse-вектор документа: индекс термина → вес BM25.
     *
     * <p>Значения берутся как {@code idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avg_len))}
     * без IDF, потому что IDF применяет Qdrant: здесь IDF считается по
     * единственному документу и был бы равен нулю.
     */
    public SparseVector encodeDocument(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return emptyVector();
        }

        Map<String, Integer> termFrequency = new LinkedHashMap<>();
        for (String token : tokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }

        Map<Integer, Double> values = new HashMap<>();
        double lengthRatio = tokens.size() / avgLength;
        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            int tf = entry.getValue();
            double saturated = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * lengthRatio));
            values.merge(indexOf(entry.getKey()), saturated, Double::sum);
        }
        return toSparseVector(values);
    }

    /**
     * Sparse-вектор как вход для поиска.
     *
     * <p>Для префетча Qdrant нужен {@code VectorInput}, а не «голый» вектор.
     * Веса термов запроса равны единице: нормировку по IDF выполняет Qdrant
     * через {@code modifier: idf} в конфигурации sparse-вектора.
     */
    public VectorInput encodeQueryInput(String query) {
        List<String> tokens = tokenize(query);
        Map<Integer, Double> values = new HashMap<>();
        for (String token : new java.util.LinkedHashSet<>(tokens)) {
            values.merge(indexOf(token), 1.0, Double::sum);
        }
        return VectorInput.newBuilder().setSparse(toSparseVector(values)).build();
    }

    private SparseVector emptyVector() {
        return toSparseVector(Map.of());
    }

    /** IDF по формуле BM25 (как в Lucene/Elasticsearch). */
    public static double idf(String token, Map<String, Long> documentFrequencies, long documentsCount) {
        long documents = Math.max(1L, documentsCount);
        long frequency = documentFrequencies == null ? 0L : documentFrequencies.getOrDefault(token, 0L);
        return Math.log(1.0 + (documents - frequency + 0.5) / (frequency + 0.5));
    }

    /**
     * Индекс терма в пространстве Qdrant: {@code uint32}.
     *
     * <p>Берётся 32-битный хэш строки; коллизии теоретически возможны, но
     * распределены равномерно, а частотный вес при коллизии суммируется, что
     * не ломает ранжирование.
     */
    public static int indexOf(String token) {
        int hash = token.hashCode();
        return hash & 0x7FFFFFFF;
    }

    /**
     * Собирает sparse-вектор Qdrant: индексы термов и их веса.
     *
     * <p>Индексы и значения идут параллельными массивами в одном
     * {@code Vector}, что соответствует sparse-представлению Qdrant.
     */
    private SparseVector toSparseVector(Map<Integer, Double> values) {
        SparseVector.Builder vector = SparseVector.newBuilder();
        new java.util.TreeMap<>(values).forEach((index, value) -> {
            vector.addIndices(index);
            vector.addValues(value.floatValue());
        });
        return vector.build();
    }
}
