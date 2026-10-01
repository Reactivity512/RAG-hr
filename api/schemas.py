"""
Pydantic-схемы API (контракт между фронтендом и бэкендом).

Не путать с common/models.py:
- common/models.py — то, что хранится в Qdrant (ResumePayload) и Redis (TaskStatus).
- api/schemas.py   — то, что отдаётся/принимается по HTTP.
Это разные слои, их смешивать нельзя: изменение формата ответа API
не должно ломать структуру данных в Qdrant.
"""

from typing import Optional

from pydantic import BaseModel, Field

from common.models import TaskStatusValue


# ─────────────────────────────────────────────────────────────
# /search
# ─────────────────────────────────────────────────────────────

class SearchRequest(BaseModel):
    query: str = Field(
        ...,
        description="Запрос пользователя (например: Нужен Java разработчик с опытом в Spring)",
    )
    top_k: int = Field(5, description="Количество резюме для передачи в LLM")
    required_skills: list[str] | None = Field(
        default=None,
        description="Обязательные навыки для жёсткого фильтра в Qdrant",
    )


class SearchResponse(BaseModel):
    answer: str
    found_resumes_count: int


# ─────────────────────────────────────────────────────────────
# /api/resumes/upload
# ─────────────────────────────────────────────────────────────

class UploadResponse(BaseModel):
    task_id: str = Field(..., description="UUID задачи — по нему отслеживать статус")
    filename: str
    status: TaskStatusValue = TaskStatusValue.QUEUED


# ─────────────────────────────────────────────────────────────
# /api/resumes  (список готовых резюме — короткая карточка)
# ─────────────────────────────────────────────────────────────

class ResumeListItem(BaseModel):
    id: str
    file_name: str
    title: Optional[str] = None
    salary: Optional[str] = None
    format: Optional[str] = None
    skills_list: list[str] = Field(default_factory=list)
    uploaded_at: str
    indexed_at: str


class ResumeListResponse(BaseModel):
    items: list[ResumeListItem]
    total: int


# ─────────────────────────────────────────────────────────────
# /api/resumes/{id}  (полная карточка)
# ─────────────────────────────────────────────────────────────

class ResumeDetail(BaseModel):
    id: str
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
    uploaded_at: str
    indexed_at: str


# ─────────────────────────────────────────────────────────────
# /api/tasks  (статусы из Redis: queued / processing / failed / indexed)
# ─────────────────────────────────────────────────────────────

class TaskItem(BaseModel):
    """Запись о задаче обработки резюме (для таблицы «в процессе / ошибки»)."""
    task_id: str
    filename: str
    status: TaskStatusValue
    uploaded_at: str
    resume_id: Optional[str] = None
    error: Optional[str] = None


class TaskListResponse(BaseModel):
    items: list[TaskItem]
    total: int


# ─────────────────────────────────────────────────────────────
# Общие
# ─────────────────────────────────────────────────────────────

class ErrorResponse(BaseModel):
    """Единый формат ошибки API."""
    detail: str