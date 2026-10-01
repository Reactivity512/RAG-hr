"""
GET /api/tasks — статусы обработки резюме из Redis.

По умолчанию возвращает только «активные» задачи:
    queued, processing, failed
Резюме со статусом indexed попадают в основную таблицу через /api/resumes.

Параметр include_indexed=true возвращает всё — для аудита/отладки.
"""

from fastapi import APIRouter, Query

from common.models import TaskStatusValue
from common.redis_ops import list_tasks as redis_list_tasks
from schemas import TaskItem, TaskListResponse


router = APIRouter(prefix="/api/tasks", tags=["tasks"])


ACTIVE_STATUSES = [
    TaskStatusValue.QUEUED,
    TaskStatusValue.PROCESSING,
    TaskStatusValue.FAILED,
]


@router.get("", response_model=TaskListResponse)
async def list_tasks(
    include_indexed: bool = Query(
        False,
        description="Включить в ответ задачи со статусом indexed (аудит)",
    ),
):
    statuses = None if include_indexed else ACTIVE_STATUSES
    tasks = redis_list_tasks(statuses=statuses)

    items = [
        TaskItem(
            task_id=t.task_id,
            filename=t.filename,
            status=t.status,
            uploaded_at=t.uploaded_at,
            resume_id=t.resume_id,
            error=t.error,
        )
        for t in tasks
    ]
    return TaskListResponse(items=items, total=len(items))