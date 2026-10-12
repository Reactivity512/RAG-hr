package com.github.reactivity512.rag.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры Qdrant и схемы коллекции.
 *
 * <p>Значения по умолчанию повторяют существующую коллекцию проекта
 * (dense 1024/cosine + sparse {@code text_bm25} с IDF), поэтому пересоздание
 * коллекции не меняет схему поиска.
 */
@ConfigurationProperties(prefix = "rag.qdrant")
public class QdrantProperties {

    /** Хост Qdrant. */
    private String host = "localhost";

    /** Порт gRPC (не REST-порт 6333). */
    private int port = 6334;

    /** Использовать TLS. */
    private boolean tls = false;

    /** API-ключ для Qdrant Cloud; пусто для локального инстанса. */
    private String apiKey = "";

    /** Имя коллекции. */
    private String collection = "resumes";

    /** Размерность dense-вектора (bge-m3). */
    private int vectorSize = 1024;

    /** Имя sparse-вектора BM25. */
    private String sparseVectorName = "text_bm25";

    /** Поле payload, по которому идёт keyword-индекс для фильтра навыков. */
    private String skillsField = "skills_list";

    /**
     * Модель BM25 со всеми опциями обработки текста.
     *
     * <p>Опции обязаны совпадать при записи и при поиске. Для русских резюме
     * дефолты Qdrant (английский стеммер и английские стоп-слова) не подходят,
     * поэтому явно задаём мультиязычный токенизатор, отсутствие стемминга,
     * пустой список стоп-слов и ASCII-folding.
     */
    private String bm25Model =
            "qdrant/bm25?tokenizer=multilingual&stemmer=none&stopwords=none&ascii_folding=true";

    /** Таймаут операций с Qdrant, мс. */
    private long timeoutMs = 30_000;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(int vectorSize) {
        this.vectorSize = vectorSize;
    }

    public String getSparseVectorName() {
        return sparseVectorName;
    }

    public void setSparseVectorName(String sparseVectorName) {
        this.sparseVectorName = sparseVectorName;
    }

    public String getSkillsField() {
        return skillsField;
    }

    public void setSkillsField(String skillsField) {
        this.skillsField = skillsField;
    }

    public String getBm25Model() {
        return bm25Model;
    }

    public void setBm25Model(String bm25Model) {
        this.bm25Model = bm25Model;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
