package com.github.reactivity512.rag.vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.ListenableFuture;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.RepeatedStrings;
import io.qdrant.client.grpc.JsonWithInt;
import com.github.reactivity512.rag.indexing.PayloadKeys;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.Document;
import io.qdrant.client.grpc.Points.Fusion;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PrefetchQuery;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.SparseVector;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.WithPayloadSelector;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.fusion;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/**
 * Схема коллекции Qdrant: создание, проверка, загрузка и чтение точек.
 *
 * <p>Коллекция содержит два вектора:
 * <ul>
 *   <li>безымянный dense (bge-m3, 1024, cosine) — семантический поиск;
 *   <li>{@code text_bm25} (sparse, IDF) — лексический поиск; sparse-вектор
 *       считает сам Qdrant по тексту ({@code qdrant/bm25}), Python-зависимости
 *       (fastembed) не нужны.
 * </ul>
 *
 * <p>Опции BM25 берутся из {@link QdrantProperties#getBm25Model()} и обязаны
 * совпадать при записи и при поиске.
 */
@Service
public class QdrantCollectionService {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionService.class);

    private final QdrantClient client;
    private final QdrantProperties properties;
    private final Bm25SparseEncoder bm25;

    /** Безымянный dense-вектор коллекции (как в конфигурации Qdrant). */
    public static final String DEFAULT_DENSE_VECTOR = "";

    public QdrantCollectionService(QdrantClient client, QdrantProperties properties, Bm25SparseEncoder bm25) {
        this.client = client;
        this.properties = properties;
        this.bm25 = bm25;
    }

    public QdrantProperties properties() {
        return properties;
    }

    public String collectionName() {
        return properties.getCollection();
    }

    // ─────────────────────────────────────────────────────────────
    // Схема коллекции
    // ─────────────────────────────────────────────────────────────

    /**
     * Создаёт коллекцию и payload-индексы, если их ещё нет.
     *
     * @return true, если коллекция была создана этим вызовом
     */
    public boolean ensureCollection() {
        if (collectionExists()) {
            verifyExistingCollection();
            return false;
        }
        createCollection();
        createPayloadIndexes();
        log.info("Коллекция '{}' создана: dense {} + sparse {}",
                properties.getCollection(), properties.getVectorSize(), properties.getSparseVectorName());
        return true;
    }

    /** Удаляет коллекцию (если есть) и создаёт заново. */
    public void recreateCollection() {
        if (collectionExists()) {
            log.warn("Удаляю коллекцию '{}' — проиндексированные резюме будут потеряны", properties.getCollection());
            await(client.deleteCollectionAsync(properties.getCollection()));
        }
        createCollection();
        createPayloadIndexes();
        log.info("Коллекция '{}' пересоздана", properties.getCollection());
    }

    private boolean collectionExists() {
        return await(client.collectionExistsAsync(properties.getCollection()));
    }

    private void createCollection() {
        VectorParams dense = VectorParams.newBuilder()
                .setSize(properties.getVectorSize())
                .setDistance(Distance.Cosine)
                .build();
        SparseVectorParams sparse = SparseVectorParams.newBuilder()
                .setModifier(Modifier.Idf)
                .build();
        CreateCollection request = CreateCollection.newBuilder()
                .setCollectionName(properties.getCollection())
                .setVectorsConfig(VectorsConfig.newBuilder().setParams(dense).build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap(properties.getSparseVectorName(), sparse)
                        .build())
                .build();
        await(client.createCollectionAsync(request));
    }

    private void createPayloadIndexes() {
        await(client.createPayloadIndexAsync(
                properties.getCollection(),
                properties.getSkillsField(),
                PayloadSchemaType.Keyword,
                null, null, null, null));
        await(client.createPayloadIndexAsync(
                properties.getCollection(),
                "full_text",
                PayloadSchemaType.Text,
                null, null, null, null));
    }

    /** Проверяет, что существующая коллекция подходит текущей конфигурации. */
    public void verifyExistingCollection() {
        CollectionInfo info = await(client.getCollectionInfoAsync(properties.getCollection()));
        Collections.VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
        long size = params.getSize();
        if (size != properties.getVectorSize()) {
            throw new IllegalStateException(String.format(
                    "Коллекция '%s' имеет размерность %d, а ожидается %d. Нужна переиндексация "
                            + "(admin-эндпоинт recreate), иначе поиск будет некорректным.",
                    properties.getCollection(), size, properties.getVectorSize()));
        }
        boolean sparsePresent = info.getConfig().getParams()
                .getSparseVectorsConfig().getMapMap().containsKey(properties.getSparseVectorName());
        if (!sparsePresent) {
            throw new IllegalStateException(String.format(
                    "В коллекции '%s' нет sparse-вектора '%s' — нужна переиндексация.",
                    properties.getCollection(), properties.getSparseVectorName()));
        }
    }

    /** Доступен ли Qdrant (для fail-fast на старте). */
    public boolean ping() {
        try {
            collectionExists();
            return true;
        } catch (RuntimeException e) {
            log.warn("Qdrant недоступен: {}", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Запись
    // ─────────────────────────────────────────────────────────────

    /** Загружает точки: dense передаётся, sparse BM25 Qdrant считает по тексту. */
    public void upsert(List<ResumeVectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<PointStruct> structs = points.stream().map(this::toPoint).toList();
        await(client.upsertAsync(properties.getCollection(), structs));
    }

    /** Строит точку: dense-вектор + sparse BM25, посчитанный локально. */
    public PointStruct toPoint(ResumeVectorPoint point) {
        Object textValue = point.payload().get(PayloadKeys.FULL_TEXT);
        SparseVector sparseVector = bm25.encodeDocument(textValue == null ? "" : textValue.toString());

        JsonWithInt.Struct.Builder payload = JsonWithInt.Struct.newBuilder();
        point.payload().forEach((key, rawValue) -> payload.putFields(key, toValue(rawValue)));

        java.util.Map<String, Vector> vectors = new java.util.LinkedHashMap<>();
        vectors.put(DEFAULT_DENSE_VECTOR, vector(point.denseVector()));
        vectors.put(properties.getSparseVectorName(), Vector.newBuilder().setSparse(sparseVector).build());

        return PointStruct.newBuilder()
                .setId(toPointId(point.id()))
                .setVectors(namedVectors(vectors))
                .putAllPayload(payload.build().getFieldsMap())
                .build();
    }

    private JsonWithInt.Value toValue(Object raw) {
        if (raw == null) {
            return io.qdrant.client.ValueFactory.nullValue();
        }
        if (raw instanceof List<?> list) {
            return value(list.stream().map(this::toValue).toList());
        }
        if (raw instanceof Map<?, ?> map) {
            java.util.Map<String, JsonWithInt.Value> converted = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> converted.put(String.valueOf(key), toValue(item)));
            return value(converted);
        }
        if (raw instanceof Boolean bool) {
            return value(bool.booleanValue());
        }
        if (raw instanceof Number number) {
            if (number instanceof Float || number instanceof Double) {
                return value(number.doubleValue());
            }
            return value(number.longValue());
        }
        return value(raw.toString());
    }

    // ─────────────────────────────────────────────────────────────
    // Чтение
    // ─────────────────────────────────────────────────────────────

    /** Payload одной точки; null, если точки нет. */
    public Map<String, Object> retrievePayload(String pointId) {
        List<Points.RetrievedPoint> records = await(client.retrieveAsync(
                properties.getCollection(), List.of(toPointId(pointId)), true, false, null));
        if (records.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        records.getFirst().getPayload().forEach((key, value) -> payload.put(key, fromValue(value)));
        return payload;
    }

    /** Список точек (scroll) для выдачи в UI. */
    public List<Points.RetrievedPoint> scroll(int limit) {
        Points.ScrollResponse response = await(client.scrollAsync(
                Points.ScrollPoints.newBuilder()
                        .setCollectionName(properties.getCollection())
                        .setLimit(limit)
                        .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                        .setWithVectors(Points.WithVectorsSelector.newBuilder().setEnable(false).build())
                        .build()));
        return response.getResultList();
    }

    /** Удаляет точку по id. */
    public void delete(String pointId) {
        await(client.deleteAsync(properties.getCollection(), List.of(toPointId(pointId))));
    }

    // ─────────────────────────────────────────────────────────────
    // Поиск
    // ─────────────────────────────────────────────────────────────

    /**
     * Гибридный поиск: dense и sparse (BM25) ищутся параллельно на стороне
     * Qdrant, результаты объединяются через Reciprocal Rank Fusion.
     *
     * <p>Опции BM25 ({@link QdrantProperties#getBm25Model()}) обязаны совпадать
     * с теми, что использовались при записи, иначе лексический поиск развалится.
     *
     * @param query           текст запроса пользователя
     * @param denseVector     эмбеддинг запроса
     * @param limit           сколько результатов вернуть после слияния
     * @param requiredSkills  обязательные навыки (жёсткий фильтр), может быть пустым
     */
    public List<Points.ScoredPoint> searchHybrid(
            String query, List<Float> denseVector, int limit, List<String> requiredSkills) {

        int prefetchLimit = Math.max(limit * 4, 20);
        PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                .setQuery(nearest(denseVector))
                .setUsing(DEFAULT_DENSE_VECTOR)
                .setLimit(prefetchLimit)
                .build();
        PrefetchQuery sparsePrefetch = PrefetchQuery.newBuilder()
                .setQuery(nearest(bm25.encodeQueryInput(query)))
                .setUsing(properties.getSparseVectorName())
                .setLimit(prefetchLimit)
                .build();

        QueryPoints.Builder request = QueryPoints.newBuilder()
                .setCollectionName(properties.getCollection())
                .addPrefetch(densePrefetch)
                .addPrefetch(sparsePrefetch)
                .setQuery(fusion(Fusion.RRF))
                .setLimit(limit)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());

        if (requiredSkills != null && !requiredSkills.isEmpty()) {
            request.setFilter(skillsFilter(requiredSkills));
        }
        return await(client.queryAsync(request.build()));
    }

    /** Фильтр «в резюме есть хотя бы один из перечисленных навыков». */
    private Filter skillsFilter(List<String> skills) {
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey(properties.getSkillsField())
                                .setMatch(Match.newBuilder()
                                        .setKeywords(RepeatedStrings.newBuilder()
                                                .addAllStrings(skills)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Утилиты
    // ─────────────────────────────────────────────────────────────

    /** Qdrant принимает id точкой-строкой только в формате UUID. */
    public static io.qdrant.client.grpc.Common.PointId toPointId(String pointId) {
        try {
            return id(UUID.fromString(pointId));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Идентификатор резюме должен быть UUID, получено: " + pointId, e);
        }
    }

    /** Значение Qdrant → обычный Java-тип (для payload из ответов). */
    public static Object fromValue(JsonWithInt.Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case NULL_VALUE -> null;
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(QdrantCollectionService::fromValue)
                    .toList();
            case STRUCT_VALUE -> {
                Map<String, Object> nested = new java.util.LinkedHashMap<>();
                value.getStructValue().getFieldsMap()
                        .forEach((key, item) -> nested.put(key, fromValue(item)));
                yield nested;
            }
            default -> null;
        };
    }

    /** Payload из точки, прочитанной через scroll. */
    public static Map<String, Object> payloadOf(Points.RetrievedPoint point) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        point.getPayload().forEach((key, value) -> payload.put(key, fromValue(value)));
        return payload;
    }

    /** Ожидание ответа Qdrant с общим таймаутом. */
    public <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа Qdrant прервано", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Ошибка Qdrant: " + e.getCause().getMessage(), e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(
                    "Qdrant не ответил за " + properties.getTimeoutMs() + " мс", e);
        }
    }
}
