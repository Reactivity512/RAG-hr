"""
POST /search — RAG-поиск: гибридный retrieval в Qdrant + генерация через LLM.

LLM-бэкенд выбирается через env LLM_BACKEND_URL:
- http://host.docker.internal:11434/v1 — локальный Ollama
- http://ollama:11434/v1               — Ollama в Docker
- http://vllm:8000/v1                  — vLLM
Все три — OpenAI-совместимые API.
"""

import os
from typing import Optional

from fastapi import APIRouter
from openai import OpenAI
from qdrant_client.models import FieldCondition, Filter, MatchAny
from qdrant_client import models

from common.embeddings import embed_query
from common.qdrant_ops import COLLECTION_NAME, get_client
from schemas import SearchRequest, SearchResponse

from celery_client import enqueue_index_resume
from common.embeddings import embed_query
from common.indexing import ResumeValidationError, validate_resume_content
from common.models import TaskStatus, TaskStatusValue
from common.redis_ops import delete_task, save_task


router = APIRouter(tags=["search"])


# ─────────────────────────────────────────────────────────────
# LLM-клиент (singleton)
# ─────────────────────────────────────────────────────────────

LLM_BACKEND_URL = os.getenv("LLM_BACKEND_URL", "http://host.docker.internal:11434/v1")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen2.5:4b")
LLM_API_KEY = os.getenv("LLM_API_KEY", "not-needed")

_llm_client: Optional[OpenAI] = None


def get_llm_client() -> OpenAI:
    global _llm_client
    if _llm_client is None:
        print(f"[search] LLM backend: {LLM_BACKEND_URL}, model: {LLM_MODEL}")
        _llm_client = OpenAI(base_url=LLM_BACKEND_URL, api_key=LLM_API_KEY)
    return _llm_client


# ─────────────────────────────────────────────────────────────
# Промпт
# ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """
Ты — опытный HR-ассистент и IT-рекрутер.
Твоя задача — проанализировать предоставленные резюме и оценить, насколько каждый кандидат соответствует запросу пользователя.

Раздели кандидатов на 4 группы по степени соответствия:

🟢 Зелёный — полностью подходит: все ключевые требования выполнены
🟡 Жёлтый — подходит с нюансами: основные требования выполнены, но есть мелкие пробелы
🟠 Оранжевый — плохо подходит: часть важных требований не выполнена
🔴 Красный — не подходит: не соответствует ключевым требованиям

Для каждого кандидата укажи:
- Имя/специальность (из резюме)
- Почему он попал в эту группу (кратко, 1–2 предложения)
- Ключевые навыки, релевантные запросу

Группы, в которых нет кандидатов, пропускай. Если ни один кандидат не подошёл — честно скажи об этом.
Не придумывай информацию, которой нет в тексте резюме.
"""


# ─────────────────────────────────────────────────────────────
# Эндпоинт
# ─────────────────────────────────────────────────────────────

@router.post("/search", response_model=SearchResponse)
async def search_resumes(request: SearchRequest):
    print("\n--- ПОЛУЧЕН НОВЫЙ ЗАПРОС ---")

    # 1. Dense-вектор запроса (BGE-M3, без префиксов)
    dense_vector = embed_query(request.query)
    print(f"[search] Размерность вектора: {len(dense_vector)}")

    # 2. Фильтр по обязательным навыкам (если переданы)
    qdrant_filter = None
    if request.required_skills:
        qdrant_filter = Filter(
            must=[
                FieldCondition(
                    key="skills_list",
                    match=MatchAny(any=request.required_skills),
                )
            ]
        )
        print(f"[search] Применён фильтр по навыкам: {request.required_skills}")

    # 3. Гибридный поиск: dense + sparse (BM25 через full_text)
    qdrant = get_client()
    search_results = qdrant.query_points(
        collection_name=COLLECTION_NAME,
        prefetch=[
            models.Prefetch(
                query=dense_vector,
                using="",
                limit=request.top_k * 4,
            ),
            models.Prefetch(
                query=models.Document(text=request.query, model="qdrant/bm25"),
                using="text_bm25",
                limit=request.top_k * 4,
            ),
        ],
        query=models.FusionQuery(fusion=models.Fusion.RRF),
        query_filter=qdrant_filter,
        limit=request.top_k,
    ).points

    print(f"[search] Qdrant вернул результатов: {len(search_results)}")

    if not search_results:
        return SearchResponse(
            answer="По вашему запросу не найдено подходящих резюме.",
            found_resumes_count=0,
        )

    # 4. Контекст для LLM
    context_text = ""
    for i, hit in enumerate(search_results, 1):
        p = hit.payload
        context_text += f"\n--- Резюме #{i} (Релевантность: {hit.score:.2f}) ---\n"
        context_text += f"Файл: {p.get('file_name', 'N/A')}\n"
        context_text += f"Специальность: {p.get('title', 'N/A')}\n"
        context_text += f"Зарплата: {p.get('salary', 'N/A')}\n"
        context_text += f"Контакты: {p.get('contacts', 'N/A')}\n"
        context_text += f"Опыт:\n{p.get('experience_text', 'N/A')}\n"
        context_text += f"Навыки: {', '.join(p.get('skills_list', []))}\n"

    user_prompt = (
        f"ЗАПРОС КАНДИДАТА:\n{request.query}\n\n"
        f"НАЙДЕННЫЕ РЕЗЮМЕ:\n{context_text}"
    )

    print(user_prompt)

    # 5. LLM
    print(f"[search] Отправляю {len(search_results)} резюме в LLM ({LLM_MODEL})")
    try:
        completion = get_llm_client().chat.completions.create(
            model=LLM_MODEL,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.1,
            max_tokens=1500,
        )
        llm_answer = completion.choices[0].message.content
    except Exception as e:
        llm_answer = (
            f"Ошибка при обращении к LLM ({LLM_BACKEND_URL}). "
            f"Убедитесь, что backend запущен и модель доступна. Ошибка: {e}"
        )

    print("[search] Ответ от LLM получен.")

    return SearchResponse(
        answer=llm_answer,
        found_resumes_count=len(search_results),
    )