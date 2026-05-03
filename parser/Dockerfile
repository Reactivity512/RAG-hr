FROM python:3.11-slim

# Отключаем буферизацию вывода и создание .pyc файлов
ENV PYTHONUNBUFFERED=1
ENV PYTHONDONTWRITEBYTECODE=1

WORKDIR /app

# 1. Кэшируем слой с зависимостями
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 2. ПРЕДЗАГРУЗКА МОДЕЛИ (Самое важное изменение!)
# Мы скачиваем 2.2 ГБ модель на этапе сборки (build), а не при старте контейнера.
# Это навсегда запечет модель в образ Docker (образ станет весить ~3 ГБ, но запуск будет мгновенным).
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('intfloat/multilingual-e5-large')"

# 3. Копируем скрипт
COPY parser_and_index.py .

# 4. Создаем пустую папку для резюме
# Если ты не пробросишь volume, скрипт не упадет с ошибкой "No such file or directory", 
# а просто скажет, что файлов 0.
RUN mkdir -p /app/resumes

# Запуск
CMD ["python", "parser_and_index.py"]