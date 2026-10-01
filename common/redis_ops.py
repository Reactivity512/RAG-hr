"""
Обёртка над Redis для хранения статусов задач обработки резюме.

Схема хранения:
    hash `resumes:tasks`
        field = task_id (str)
        value = JSON(TaskStatus)
"""

import os
from typing import Optional

import redis

from common.models import TaskStatus, TaskStatusValue


TASKS_HASH_KEY = "resumes:tasks"

_client: Optional[redis.Redis] = None


# ─────────────────────────────────────────────────────────────
# Клиент
# ─────────────────────────────────────────────────────────────

def get_client() -> redis.Redis:
    """Singleton-клиент Redis (создаётся один раз на процесс)."""
    global _client
    if _client is None:
        url = os.getenv("REDIS_URL", "redis://localhost:6379/0")
        print(f"[redis_ops] Подключение к {url}...")
        _client = redis.from_url(url, decode_responses=True)
    return _client


def ping() -> bool:
    """Проверка соединения. Используется при старте API/worker."""
    try:
        return get_client().ping()
    except redis.RedisError as e:
        print(f"[redis_ops] Redis недоступен: {e}")
        return False


# ─────────────────────────────────────────────────────────────
# CRUD задач
# ─────────────────────────────────────────────────────────────

def save_task(task: TaskStatus) -> None:
    """Записывает или обновляет задачу (HSET)."""
    get_client().hset(
        TASKS_HASH_KEY,
        task.task_id,
        task.model_dump_json(),
    )


def get_task(task_id: str) -> Optional[TaskStatus]:
    """Читает одну задачу по task_id. None, если нет."""
    raw = get_client().hget(TASKS_HASH_KEY, task_id)
    if raw is None:
        return None
    return TaskStatus.model_validate_json(raw)


def list_tasks(
    statuses: Optional[list[TaskStatusValue]] = None,
) -> list[TaskStatus]:
    """
    Возвращает список задач, отсортированный по uploaded_at (свежие первыми).

    statuses=None — все задачи.
    statuses=[QUEUED, PROCESSING, FAILED] — только «в работе» и ошибки.
    """
    raw_all = get_client().hgetall(TASKS_HASH_KEY)

    tasks: list[TaskStatus] = []
    for raw in raw_all.values():
        task = TaskStatus.model_validate_json(raw)
        if statuses is None or task.status in statuses:
            tasks.append(task)

    tasks.sort(key=lambda t: t.uploaded_at, reverse=True)
    return tasks


def delete_task(task_id: str) -> None:
    """Удаляет задачу из Redis (HDEL)."""
    get_client().hdel(TASKS_HASH_KEY, task_id)