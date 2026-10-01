"""
Celery-клиент для API.

Отправляет задачи воркеру по имени — код задачи живёт в parser/tasks.py,
"""

import os

from celery import Celery


CELERY_BROKER_URL = os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0")
CELERY_RESULT_BACKEND = os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/1")


celery_client = Celery(
    "rag_hr_api",
    broker=CELERY_BROKER_URL,
    backend=CELERY_RESULT_BACKEND,
)


def enqueue_index_resume(task_id: str, file_name: str) -> str:
    """
    Отправляет задачу обработки резюме воркеру.
    Возвращает task_id (тот же, что передан — используется как ключ в Redis).
    """
    celery_client.send_task(
        "index_resume",
        args=[task_id, file_name],
        task_id=task_id,  # используем наш UUID как Celery task_id — для отладки
    )
    return task_id