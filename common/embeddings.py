"""
Singleton-обёртка над SentenceTransformer для модели BGE-M3.
"""

from functools import lru_cache

from sentence_transformers import SentenceTransformer


EMBEDDING_MODEL_NAME = "BAAI/bge-m3"
MAX_TOKENS = 8192  # контекст BGE-M3


@lru_cache(maxsize=1)
def get_encoder() -> SentenceTransformer:
    """Singleton-экземпляр модели BGE-M3."""
    print(f"[embeddings] Загрузка модели: {EMBEDDING_MODEL_NAME}...")
    model = SentenceTransformer(EMBEDDING_MODEL_NAME)
    print(f"[embeddings] Модель загружена.")
    return model


def embed_query(text: str) -> list[float]:
    """Эмбеддинг поискового запроса (без префикса)."""
    encoder = get_encoder()
    return encoder.encode(text).tolist()


def embed_passage(text: str) -> list[float]:
    """Эмбеддинг документа-резюме (без префикса)."""
    encoder = get_encoder()
    return encoder.encode(text).tolist()

def count_tokens(text: str) -> int:
    """Точный подсчёт токенов через токенизатор модели."""
    encoder = get_encoder()
    return len(encoder.tokenizer.encode(text, add_special_tokens=False))