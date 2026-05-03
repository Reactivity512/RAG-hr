import os
import ollama
from fastapi import FastAPI
from pydantic import BaseModel, Field
from qdrant_client import QdrantClient
from qdrant_client.models import Filter, FieldCondition, MatchAny
from sentence_transformers import SentenceTransformer
from fastapi.staticfiles import StaticFiles

# --- КОНФИГУРАЦИЯ ---
QDRANT_HOST = os.getenv("QDRANT_HOST", "localhost")
QDRANT_URL = f"http://{QDRANT_HOST}:6333"
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://localhost:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:4b") # модель
COLLECTION_NAME = "resumes"
EMBEDDING_MODEL_NAME = "intfloat/multilingual-e5-large"

# --- ИНИЦИАЛИЗАЦИЯ ---
app = FastAPI(title="RAG Resume API")

print(f"Загрузка модели эмбеддингов: {EMBEDDING_MODEL_NAME}...")
encoder = SentenceTransformer(EMBEDDING_MODEL_NAME)

print(f"Подключение к Qdrant: {QDRANT_URL}...")
qdrant = QdrantClient(url=QDRANT_URL)

# --- Pydantic схемы ---
class SearchRequest(BaseModel):
    query: str = Field(..., description="Запрос пользователя (например: Нужен Java разработчик с опытом в Spring)")
    top_k: int = Field(5, description="Количество резюме для передачи в LLM")
    required_skills: list[str] | None = Field(default=None, description="Обязательные навыки для жесткого фильтра в Qdrant")

class SearchResponse(BaseModel):
    answer: str
    found_resumes_count: int

# --- Промпт для LLM ---
SYSTEM_PROMPT = """
Ты — опытный HR-ассистент и IT-рекрутер. 
Твоя задача — проанализировать предоставленные тексты резюме и ответить на вопрос пользователя.
На основе резюме составь краткий список подходящих кандидатов. 
Для каждого кандидата укажи: Имя/Специальность, релевантный опыт, ключевые навыки и почему он подходит под запрос.
Если ни один кандидат не подходит, так и скажи. Не придумывай информацию, которой нет в тексте.
"""

@app.post("/search", response_model=SearchResponse)
async def search_resumes(request: SearchRequest):
    print("\n--- ПОЛУЧЕН НОВЫЙ ЗАПРОС ---")

    # 1. Формируем Dense вектор (префикс "query: " для E5)
    dense_vector = encoder.encode(f"query: {request.query}").tolist()
    print(f"Размерность вектора: {len(dense_vector)}")

    # 2. Строим фильтр (если переданы обязательные навыки)
    qdrant_filter = None
    if request.required_skills:
        qdrant_filter = Filter(
            must=[
                FieldCondition(key="skills_list", match=MatchAny(any=request.required_skills))
            ]
        )
        print(f"Применен жесткий фильтр Qdrant")

    # 3. ПОИСК В QDRANT
    print(f"Выполняю поиск по запросу в Qdrant: '{request.query}'...")
    
    search_results = qdrant.query_points(
        collection_name=COLLECTION_NAME,
        query=dense_vector,                 # Dense вектор от E5
        query_filter=qdrant_filter,         # Фильтр по жестким навыкам
        # Ищем точные совпадения слов через BM25 (Sparse)
        query_sparse={
            "text": request.query           # Qdrant сам сделает Sparse-вектор из текста
        }, 
        # Формула объединения (например, 70% Dense, 30% Sparse)
        rank={
            "fusion": "rrf",
            "weights": {
                "dense": 0.7,
                "sparse": 0.3
            }
        },
        limit=request.top_k
    ).points

    print(f"Qdrant вернул результатов: {len(search_results)}")

    if not search_results:
        return SearchResponse(answer="По вашему запросу не найдено подходящих резюме.", found_resumes_count=0)

    # 4. Собираем контекст из найденных резюме
    context_text = ""
    for i, hit in enumerate(search_results, 1):
        payload = hit.payload
        context_text += f"\n--- Резюме #{i} (Релевантность: {hit.score:.2f}) ---\n"
        context_text += f"Файл: {payload.get('file_name', 'N/A')}\n"
        context_text += f"Специальность: {payload.get('title', 'N/A')}\n"
        context_text += f"Зарплата: {payload.get('salary', 'N/A')}\n"
        context_text += f"Контакты: {payload.get('contacts', 'N/A')}\n"
        context_text += f"Опыт:\n{payload.get('experience_text', 'N/A')}\n"
        context_text += f"Навыки: {', '.join(payload.get('skills_list', []))}\n"

    # 5. Формируем финальный промпт для Ollama
    user_prompt = f"ЗАПРОС КАНДИДАТА:\n{request.query}\n\nНАЙДЕННЫЕ РЕЗЮМЕ:\n{context_text}"

    # 6. Асинхронный вызов Ollama (используем OpenAI-совместимый эндпоинт)
    ollama_url = f"{OLLAMA_HOST}/api/chat"
    
    print(f"Отправляю {len(search_results)} резюме в Ollama ({OLLAMA_MODEL})")
    
    try:
        response = ollama.chat(
            model='qwen3:4b',
            messages=[
                {'role': 'system', 'content': SYSTEM_PROMPT},
                {'role': 'user', 'content': user_prompt}
            ],
            options={
                'temperature': 0.1,
                'num_ctx': 8192 # 4096 мало для 5 резюме. Лучше 8192
            }
        )
        
        llm_answer = response['message']['content']
        
    except Exception as e:
        llm_answer = f"Ошибка при обращении к локальной Ollama. Убедитесь, что сервер запущен и модель скачана. Ошибка: {str(e)}"

    print("Ответ от LLM получен.")
    
    return SearchResponse(
        answer=llm_answer,
        found_resumes_count=len(search_results)
    )

@app.get("/health")
async def health():
    return {"status": "ok"}

app.mount("/", StaticFiles(directory="static", html=True), name="static")