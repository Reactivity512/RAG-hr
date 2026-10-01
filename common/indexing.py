"""
Оркестрация обработки одного резюме: чтение → парсинг → валидация
→ проверка размера → эмбеддинг → upsert в Qdrant.

Используется и Celery-задачей (tasks.py), и CLI (parser_and_index.py).
"""

import uuid

from common.embeddings import count_tokens, embed_passage, MAX_TOKENS
from common.models import ResumePayload
from common.parsing import parse_resume_txt, validate_resume
from common.qdrant_ops import upsert_resume


class ResumeValidationError(Exception):
    """Резюме не прошло валидацию: формат или превышен размер."""

def _validate_content(text: str, file_name: str) -> dict:
    """Парсинг + валидация + проверка размера. Без IO."""
    payload = parse_resume_txt(text, file_name)

    missing = validate_resume(payload)
    if missing:
        raise ResumeValidationError(
            f"Файл не соответствует шаблону. Отсутствуют секции: {', '.join(missing)}"
        )

    tokens = count_tokens(text)
    if tokens > MAX_TOKENS:
        raise ResumeValidationError(
            f"Резюме слишком большое: {tokens} токенов (лимит {MAX_TOKENS})"
        )

    return payload


def validate_resume_content(text: str, file_name: str) -> None:
    """Валидация in-memory (для API: до сохранения на диск)."""
    _validate_content(text, file_name)


def _load_and_validate(file_path: str, file_name: str) -> dict:
    """Читает файл, парсит, валидирует. Для worker/CLI."""
    with open(file_path, "r", encoding="utf-8") as f:
        text = f.read()
    return _validate_content(text, file_name)


def validate_resume_file(file_path: str, file_name: str) -> None:
    """
    Только валидация, без индексации.
    Используется API перед enqueue — чтобы сразу вернуть 422 пользователю.
    """
    _load_and_validate(file_path, file_name)


def process_and_index_resume(file_path: str, file_name: str) -> str:
    """Полный пайплайн: парсинг → эмбеддинг → upsert. Возвращает resume_id."""
    resume_id, vector, payload = process_resume_to_item(file_path, file_name)
    upsert_resume(resume_id, vector, payload)
    return resume_id

def process_resume_to_item(file_path: str, file_name: str) -> tuple[str, list[float], dict]:
    """
    Обрабатывает один файл без записи в Qdrant.
    Возвращает (resume_id, dense_vector, payload_dict) — для батчевой загрузки.
    """
    payload_dict = _load_and_validate(file_path, file_name)
    vector = embed_passage(payload_dict["full_text"])
    resume_id = str(uuid.uuid4())
    payload = ResumePayload.from_parsed(payload_dict)
    return resume_id, vector, payload.model_dump()