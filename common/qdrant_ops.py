"""
Бизнес-логика (парсинг → эмбеддинг → upsert) живёт в worker/tasks.py и api/main.py.
"""

import os
from typing import Optional

from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance,
    Modifier,
    PointStruct,
    SparseVectorParams,
    TextIndexParams,
    TokenizerType,
    VectorParams,
)


# ─────────────────────────────────────────────────────────────
# Конфигурация
# ─────────────────────────────────────────────────────────────

COLLECTION_NAME = "resumes"
VECTOR_SIZE = 1024  # multilingual-e5-large


def _qdrant_url() -> str:
    host = os.getenv("QDRANT_HOST", "localhost")
    return f"http://{host}:6333"


# ─────────────────────────────────────────────────────────────
# Клиент
# ─────────────────────────────────────────────────────────────

_client: Optional[QdrantClient] = None


def get_client() -> QdrantClient:
    """Singleton-клиент Qdrant (создаётся один раз на процесс)."""
    global _client
    if _client is None:
        url = _qdrant_url()
        print(f"[qdrant_ops] Подключение к {url}...")
        _client = QdrantClient(url=url)
    return _client


# ─────────────────────────────────────────────────────────────
# Инициализация коллекции
# ─────────────────────────────────────────────────────────────

def ensure_collection() -> None:
    """
    Создаёт коллекцию и payload-индексы, если их нет.
    Коллекция содержит два вектора:
      - "" (безымянный dense, BGE-M3, 1024)
      - "text_bm25" (sparse, генерируется Qdrant из текста, BM25)
    """
    client = get_client()

    if client.collection_exists(COLLECTION_NAME):
        print(f"[qdrant_ops] Коллекция '{COLLECTION_NAME}' уже существует.")
        return

    client.create_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=VectorParams(size=VECTOR_SIZE, distance=Distance.COSINE),
        sparse_vectors_config={
            "text_bm25": SparseVectorParams(modifier=Modifier.IDF),
        },
    )

    # payload-индексы для фильтрации
    client.create_payload_index(
        collection_name=COLLECTION_NAME,
        field_name="skills_list",
        field_schema="keyword",
    )

    print(f"[qdrant_ops] Коллекция '{COLLECTION_NAME}' создана + payload-индексы.")

# ─────────────────────────────────────────────────────────────
# CRUD
# ─────────────────────────────────────────────────────────────

def upsert_resume(resume_id: str, vector: list[float], payload: dict) -> None:
    """
    Загружает одну точку (одно резюме) в Qdrant с двумя векторами:
    dense (передан) + sparse BM25 (генерируется Qdrant из full_text).
    """
    client = get_client()
    client.upsert(
        collection_name=COLLECTION_NAME,
        points=[PointStruct(
            id=resume_id,
            vector={
                "": vector,
                "text_bm25": Document(
                    text=payload["full_text"],
                    model="qdrant/bm25",
                ),
            },
            payload=payload,
        )],
    )


def upsert_batch(items: list[tuple[str, list[float], dict]]) -> None:
    """
    Батчевая загрузка точек (для CLI-переиндексации).

    Принимает список кортежей (resume_id, dense_vector, payload).
    Sparse-вектор генерируется Qdrant'ом из payload["full_text"].
    """
    if not items:
        return

    points = [
        PointStruct(
            id=resume_id,
            vector={
                "": dense_vector,
                "text_bm25": Document(
                    text=payload["full_text"],
                    model="qdrant/bm25",
                ),
            },
            payload=payload,
        )
        for resume_id, dense_vector, payload in items
    ]

    client = get_client()
    client.upsert(collection_name=COLLECTION_NAME, points=points)


def list_resumes(limit: int = 100, offset: Optional[str] = None):
    """Список резюме (scroll). Возвращает (points, next_offset)."""
    client = get_client()
    return client.scroll(
        collection_name=COLLECTION_NAME,
        limit=limit,
        offset=offset,
        with_payload=True,
        with_vectors=False,
    )


def get_resume(resume_id: str) -> Optional[dict]:
    """Одна точка по id. Возвращает payload или None."""
    client = get_client()
    records = client.retrieve(
        collection_name=COLLECTION_NAME,
        ids=[resume_id],
        with_payload=True,
        with_vectors=False,
    )
    if not records:
        return None
    return records[0].payload


def delete_resume(resume_id: str) -> None:
    """Удаляет точку по id."""
    client = get_client()
    client.delete(
        collection_name=COLLECTION_NAME,
        points_selector=[resume_id],
    )

def recreate_collection() -> None:
    """
    Удаляет коллекцию (если есть) и создаёт заново.
    Используется CLI-переиндексацией для чистого rebuild без дублей.
    """
    client = get_client()
    if client.collection_exists(COLLECTION_NAME):
        print(f"[qdrant_ops] Удаляю коллекцию '{COLLECTION_NAME}'...")
        client.delete_collection(COLLECTION_NAME)
    ensure_collection()