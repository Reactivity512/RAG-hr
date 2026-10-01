"""
Celery-задача обработки одного загруженного резюме.

Поток статусов в Redis:
    (API создаёт QUEUED) → PROCESSING → INDEXED / FAILED
"""

import os
from datetime import datetime, timezone

from celery_app import celery_app
from common.indexing import ResumeValidationError, process_and_index_resume
from common.models import TaskStatus, TaskStatusValue
from common.redis_ops import get_task, save_task


RESUMES_DIR = os.getenv("RESUMES_DIR", "./resumes")


def _update_status(
    task_id: str,
    file_name: str,
    status: TaskStatusValue,
    resume_id: str | None = None,
    error: str | None = None,
) -> None:
    """
    Обновляет статус задачи, сохраняя исходный uploaded_at из Redis.
    Если записи нет (не должна возникать в нормальном потоке) — создаёт с now.
    """
    existing = get_task(task_id)
    uploaded_at = (
        existing.uploaded_at
        if existing
        else datetime.now(timezone.utc).isoformat()
    )

    save_task(
        TaskStatus(
            task_id=task_id,
            filename=file_name,
            status=status,
            uploaded_at=uploaded_at,
            resume_id=resume_id,
            error=error,
        )
    )


@celery_app.task(name="index_resume")
def index_resume(task_id: str, file_name: str) -> str | None:
    """
    Обрабатывает один файл резюме: парсинг → эмбеддинг → upsert в Qdrant.

    При успехе возвращает resume_id (UUID точки в Qdrant).
    При ошибке — пишет FAILED в Redis и re-raise (для логов Celery).
    """
    file_path = os.path.join(RESUMES_DIR, file_name)

    print(f"[index_resume] task_id={task_id} file={file_name} — старт")
    _update_status(task_id, file_name, TaskStatusValue.PROCESSING)

    try:
        resume_id = process_and_index_resume(file_path, file_name)
    except ResumeValidationError as e:
        print(f"[index_resume] Валидация не пройдена: {e}")
        _update_status(task_id, file_name, TaskStatusValue.FAILED, error=str(e))
        raise
    except Exception as e:
        print(f"[index_resume] Системная ошибка: {e}")
        _update_status(
            task_id, file_name, TaskStatusValue.FAILED,
            error=f"Внутренняя ошибка: {type(e).__name__}: {e}",
        )
        raise

    print(f"[index_resume] Готово: resume_id={resume_id}")
    _update_status(task_id, file_name, TaskStatusValue.INDEXED, resume_id=resume_id)
    return resume_id