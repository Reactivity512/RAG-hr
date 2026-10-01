FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# Зависимости
COPY parser/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Предзагрузка модели BGE-M3 в образ (~2.2 GB)
# Модель запекается в образ, при старте контейнера не скачивается
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('BAAI/bge-m3')"

# Общий пакет (копируется в оба образа: api и parser)
COPY common/ ./common/

# Код парсера
COPY parser/celery_app.py .
COPY parser/tasks.py .
COPY parser/parser_and_index.py .

# Папка для резюме (при запуске перекрывается volume)
RUN mkdir -p /app/resumes

# Непривилегированный пользователь
RUN useradd -m appuser && chown -R appuser:appuser /app
USER appuser

# По умолчанию — CLI (переопределяется в compose для worker'а и CLI-профиля)
CMD ["python", "parser_and_index.py"]