FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    APP_MODULE=main:app \
    PORT=8000

WORKDIR /app

# Зависимости API
COPY api/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Предзагрузка модели BGE-M3 (нужна для embed_query в /search)
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Общий пакет
COPY common/ ./common/

# Код API
COPY api/main.py .
COPY api/celery_client.py .
COPY api/static/ ./static/
COPY api/schemas.py .
COPY api/routes/ ./routes/

RUN useradd -m appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE ${PORT}

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:${PORT}/health')" || exit 1

CMD ["sh", "-c", "uvicorn ${APP_MODULE} --host 0.0.0.0 --port ${PORT} --log-level info"]