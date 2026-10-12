# ─────────────────────────────────────────────────────────────
# Сборка
# ─────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Сначала только манифест: слой с зависимостями кэшируется
COPY rag/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY rag/src ./src
RUN mvn -B -q clean package -DskipTests

# ─────────────────────────────────────────────────────────────
# Запуск
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
WORKDIR /app

# Непривилегированный пользователь
RUN useradd -m -u 1001 appuser \
    && mkdir -p /app/resumes \
    && chown -R appuser:appuser /app
USER appuser

COPY --from=build /workspace/target/rag-hr.jar /app/rag-hr.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8" \
    RESUMES_DIR=/app/resumes \
    PORT=8000

EXPOSE ${PORT}

# По умолчанию — API; воркер переопределяет entrypoint в compose
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/rag-hr.jar --spring.profiles.active=api \"$@\"", "--"]

# В slim-образе нет wget/curl, а /dev/tcp умеет только bash (не dash),
# поэтому живость проверяется открытием порта именно через bash
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} || exit 1"]
