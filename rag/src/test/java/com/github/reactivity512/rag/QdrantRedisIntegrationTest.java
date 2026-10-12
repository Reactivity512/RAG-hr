package com.github.reactivity512.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.github.reactivity512.rag.indexing.PayloadKeys;
import com.github.reactivity512.rag.indexing.ResumeIndexingService;
import com.github.reactivity512.rag.vector.QdrantCollectionService;

import io.qdrant.client.grpc.Points.ScoredPoint;

/**
 * Интеграционный тест на живых Qdrant и Redis (Testcontainers).
 *
 * <p>Проверяет то, что нельзя проверить модульно: создание коллекции по
 * ожидаемой схеме, загрузку точки с двумя векторами (dense + sparse),
 * чтение payload по ключам и гибридный поиск с объединением через RRF.
 *
 * <p>Ollama не нужна: вместо модели эмбеддингов подставляется
 * детерминированная заглушка той же размерности. Тест проверяет конвейер и
 * схему данных, а не качество векторов.
 */
@SpringBootTest(classes = {RagApiApplication.class, QdrantRedisIntegrationTest.StubEmbeddingConfig.class})
@Testcontainers
@ActiveProfiles("worker")
class QdrantRedisIntegrationTest {

    private static final int DIMENSIONS = 1024;
    private static final String QUERY = "Java Spring Boot Kafka разработчик";

    /** Qdrant: приложению нужен gRPC-порт 6334. */
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>(
            DockerImageName.parse("qdrant/qdrant:latest"))
            .withExposedPorts(6333, 6334);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("rag.qdrant.host", QDRANT::getHost);
        registry.add("rag.qdrant.port", () -> QDRANT.getMappedPort(6334));
        registry.add("rag.qdrant.collection", () -> "resumes_it");
        registry.add("rag.qdrant.timeout-ms", () -> "20000");
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
        registry.add("rag.queue.enabled", () -> "false");
        registry.add("rag.embedding.fail-fast", () -> "false");
        registry.add("rag.storage.resumes-dir", () -> "target/it-resumes");
    }

    @Autowired
    private QdrantCollectionService qdrant;

    @Autowired
    private ResumeIndexingService indexingService;

    @Test
    @DisplayName("резюме индексируется, payload читается по ключам, гибридный поиск его находит")
    void indexesAndFindsResume() throws Exception {
        String text = readSample("sample-java-moscow-vs-spb.txt");
        ResumeIndexingService.IndexingResult result = indexingService.index(
                text.getBytes(StandardCharsets.UTF_8), "integration.txt", "integration.txt", null);

        // id детерминированный: sha256 нормализованного текста -> UUID
        String expectedId = ResumeIndexingService.resumeIdFromHash(
                ResumeIndexingService.sha256(result.resume().fullText()));
        assertThat(result.resumeId()).isEqualTo(expectedId);

        Map<String, Object> payload = qdrant.retrievePayload(result.resumeId());
        assertThat(payload).isNotNull();
        assertThat(payload.get(PayloadKeys.NAME)).isEqualTo("Смирнова Анна");
        assertThat(payload.get(PayloadKeys.GENDER)).isEqualTo("Женщина");
        assertThat(payload.get(PayloadKeys.TITLE)).isEqualTo("Java разработчик");
        assertThat(payload.get(PayloadKeys.CONTENT_HASH)).isNotNull();
        assertThat(String.valueOf(payload.get(PayloadKeys.FULL_TEXT))).contains("Опыт работы");

        assertThat((List<?>) payload.get(PayloadKeys.SKILLS)).asList()
                .contains("Java", "Spring Boot", "Kafka");
        assertThat((List<?>) payload.get(PayloadKeys.EXPERIENCE)).hasSize(2);
        assertThat((List<?>) payload.get(PayloadKeys.EDUCATION)).hasSize(1);

        // Гибридный поиск: dense + sparse, объединение через RRF
        List<ScoredPoint> hits = qdrant.searchHybrid(
                QUERY, toFloatList(stubVector(QUERY)), 5, List.of());
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().getId().getUuid()).isEqualTo(result.resumeId());
    }

    @Test
    @DisplayName("жёсткий фильтр по навыкам отсекает несовпадающие резюме")
    void skillsFilterIsApplied() throws Exception {
        String text = readSample("sample-data-engineer.txt");
        indexingService.index(text.getBytes(StandardCharsets.UTF_8), "data.txt", "data.txt", null);

        List<Float> queryVector = toFloatList(stubVector("инженер данных"));
        List<ScoredPoint> matching = qdrant.searchHybrid(
                "инженер данных", queryVector, 10, List.of("Airflow", "ClickHouse"));
        List<ScoredPoint> notMatching = qdrant.searchHybrid(
                "инженер данных", queryVector, 10, List.of("НетТакогоНавыка"));

        assertThat(matching).isNotEmpty();
        assertThat(notMatching).isEmpty();
    }

    @Test
    @DisplayName("коллекция создаётся с ожидаемой схемой и доступна")
    void collectionIsAvailable() {
        assertThat(qdrant.collectionName()).isEqualTo("resumes_it");
        assertThat(qdrant.ping()).isTrue();
    }

    private String readSample(String fileName) throws Exception {
        return Files.readString(Path.of("..", "resumes", "samples", fileName), StandardCharsets.UTF_8);
    }

    private static List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    /** Детерминированный ненулевой вектор: проверяем конвейер, а не качество. */
    private static float[] stubVector(String text) {
        float[] vector = new float[DIMENSIONS];
        int seed = Math.abs(text.hashCode());
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = ((seed + i * 31) % 1000) / 1000f;
        }
        return vector;
    }

    /** Заглушка вместо Ollama: тест не должен зависеть от внешней модели. */
    @TestConfiguration
    static class StubEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new EmbeddingModel() {

                @Override
                public float[] embed(Document document) {
                    return stubVector(document.getText());
                }

                @Override
                public float[] embed(String text) {
                    return stubVector(text);
                }

                @Override
                public int dimensions() {
                    return DIMENSIONS;
                }

                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> embeddings = new ArrayList<>();
                    List<String> instructions = request.getInstructions();
                    for (int i = 0; i < instructions.size(); i++) {
                        embeddings.add(new Embedding(stubVector(instructions.get(i)), i));
                    }
                    return new EmbeddingResponse(embeddings);
                }
            };
        }
    }
}
