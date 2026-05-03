import os
import re
import uuid
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct, TextIndexParams, TokenizerType
from sentence_transformers import SentenceTransformer

# --- КОНФИГУРАЦИЯ ---
RESUMES_DIR = os.getenv("RESUMES_DIR", "./resumes") # Папка с txt файлами
QDRANT_HOST = os.getenv("QDRANT_HOST", "localhost") # По умолчанию localhost, но в докере подставится 'qdrant'
QDRANT_URL = f"http://{QDRANT_HOST}:6333"           # Формируем URL динамически
COLLECTION_NAME = "resumes"
EMBEDDING_MODEL_NAME = "intfloat/multilingual-e5-large"
VECTOR_SIZE = 1024     # Размерность для multilingual-e5-large

def parse_resume_txt(text: str, file_name: str) -> dict:
    """
    Парсит TXT резюме по заданному шаблону и извлекает структурированные данные.
    """
    payload = {
        "file_name": file_name,
        "title": None,
        "salary": None,
        "format": None,
        "contacts": None,
        "experience_text": None,
        "skills_list": [],           # Список для фильтрации в Qdrant
        "education": None,
        "about": None,
        "full_text": text            # Полный текст для генерации ответа LLM
    }

    # Регулярки для извлечения секций (re.DOTALL чтобы "." захватывал переносы строк)
    patterns = {
        "title": r"Название специальности:\s*(.*?)\n\n",
        "salary": r"Заработная плата:\s*(.*?)\n\n",
        "format": r"Формат работы:\s*(.*?)\n\n",
        "contacts": r"Контакты:\s*(.*?)\n\n",
        "experience_text": r"Опыт работы:\s*(.*?)\n\nНавыки:",
        "education": r"Образование:\s*(.*?)\n\n",
        "about": r"Об себе:\s*(.*)"
    }

    for key, pattern in patterns.items():
        match = re.search(pattern, text, re.DOTALL)
        if match:
            payload[key] = match.group(1).strip()

    # Специальная обработка навыков: вытащить чистые названия без уровня в скобках
    skills_match = re.search(r"Навыки:\s*(.*?)\n\nОбразование:", text, re.DOTALL)
    if skills_match:
        raw_skills = skills_match.group(1).strip().split('\n')
        # Берем только название навыка до открывающей скобки, если она есть
        clean_skills = [re.sub(r'\s*\(.*?\)', '', skill).strip() for skill in raw_skills if skill.strip()]
        payload["skills_list"] = clean_skills

    return payload

def main():
    print(f"Загрузка модели эмбеддингов: {EMBEDDING_MODEL_NAME}...")
    # Модель скачается при первом запуске (около 2.2 ГБ)
    encoder = SentenceTransformer(EMBEDDING_MODEL_NAME)

    print(f"Подключение к Qdrant: {QDRANT_URL}...")
    client = QdrantClient(url=QDRANT_URL)

    # Создаем коллекцию, если ее нет
    if not client.collection_exists(COLLECTION_NAME):
        client.create_collection(
            collection_name=COLLECTION_NAME,
            vectors_config=VectorParams(size=VECTOR_SIZE, distance=Distance.COSINE),
        )

        # Включаем полнотекстовый поиск (Sparse/BM25) по полю full_text
        client.create_payload_index(
            collection_name=COLLECTION_NAME,
            field_name="full_text",
            index_params=TextIndexParams(
                type="text",
                tokenizer=TokenizerType.WORD # Указываем токенизатор по словам (для русского и английского)
            )
        )
        print(f"Коллекция '{COLLECTION_NAME}' успешно создана.")
    else:
        print(f"Коллекция '{COLLECTION_NAME}' уже существует.")

    # Собираем все файлы
    txt_files = [f for f in os.listdir(RESUMES_DIR) if f.endswith('.txt')]
    if not txt_files:
        print(f"В папке {RESUMES_DIR} не найдено .txt файлов.")
        return

    points_to_upload = []

    print(f"Найдено файлов: {len(txt_files)}. Начинаю парсинг и векторизацию...")

    for file_name in txt_files:
        file_path = os.path.join(RESUMES_DIR, file_name)
        
        with open(file_path, 'r', encoding='utf-8') as f:
            text = f.read()

        # 1. Парсим текст
        payload = parse_resume_txt(text, file_name)

        # 2. Создаем эмбеддинг (ВАЖНО: для e5 добавляем префикс "passage: ")
        text_to_embed = f"passage: {payload['full_text']}"
        vector = encoder.encode(text_to_embed).tolist()

        # 3. Формируем точку для Qdrant
        point = PointStruct(
            id=str(uuid.uuid4()), # Уникальный ID
            vector=vector,
            payload=payload
        )
        points_to_upload.append(point)

    # 4. Пакетная загрузка в Qdrant (намного быстрее чем по одной)
    client.upsert(
        collection_name=COLLECTION_NAME,
        points=points_to_upload
    )

    print(f"Успешно проиндексировано {len(points_to_upload)} резюме в Qdrant!")

if __name__ == "__main__":
    main()