"""
Pydantic-схемы, общие для API и parser-worker.

ResumePayload  — payload точки в Qdrant (готовое резюме).
TaskStatus     — статус задачи в Redis (queued/processing/indexed/failed).
"""

from datetime import datetime, timezone
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


# ─────────────────────────────────────────────────────────────
# Резюме в Qdrant
# ─────────────────────────────────────────────────────────────

class ResumePayload(BaseModel):
    """Payload точки в коллекции Qdrant. Одна точка = одно готовое резюме."""

    file_name: str
    title: Optional[str] = None
    salary: Optional[str] = None
    format: Optional[str] = None
    contacts: Optional[str] = None
    experience_text: Optional[str] = None
    skills_list: list[str] = Field(default_factory=list)
    education: Optional[str] = None
    about: Optional[str] = None
    full_text: str

    # Метаданные
    uploaded_at: str
    indexed_at: str

    @classmethod
    def from_parsed(cls, parsed: dict, uploaded_at: Optional[str] = None) -> "ResumePayload":
        now = datetime.now(timezone.utc).isoformat()
        return cls(
            **parsed,
            uploaded_at=uploaded_at or now,
            indexed_at=now,
        )


# ─────────────────────────────────────────────────────────────
# Статусы задач в Redis
# ─────────────────────────────────────────────────────────────

class TaskStatusValue(str, Enum):
    """Статусы обработки резюме."""
    QUEUED = "queued"
    PROCESSING = "processing"
    INDEXED = "indexed"
    FAILED = "failed"


class TaskStatus(BaseModel):
    """Запись о задаче в Redis hash `resumes:tasks`."""

    task_id: str
    filename: str
    status: TaskStatusValue
    uploaded_at: str
    resume_id: Optional[str] = None   # id точки в Qdrant, когда INDEXED
    error: Optional[str] = None       # текст ошибки, когда FAILED
