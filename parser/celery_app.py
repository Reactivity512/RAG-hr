"""
Celery-приложение для обработки загруженных резюме.
"""

import os

from celery import Celery
from celery.signals import worker_ready

from common.qdrant_ops import ensure_collection
from common.redis_ops import ping as redis_ping


CELERY_BROKER_URL = os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0")
CELERY_RESULT_BACKEND = os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/1")


celery_app = Celery(
    "rag_hr",
    broker=CELERY_BROKER_URL,
    backend=CELERY_RESULT_BACKEND,
)


celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,        # STARTED-статус пишется в result backend
    task_acks_late=True,            # ack после завершения (защита от падения worker'а)
    worker_prefetch_multiplier=1,   # по одной задаче за раз — важно для concurrency=1
    imports=("tasks",),             # автозагрузка задач из tasks.py
)


@worker_ready.connect
def _on_worker_ready(sender, **kwargs):
    """При старте worker'а: проверить Redis и создать коллекцию Qdrant."""
    print("[celery_app] Worker стартует...")

    if not redis_ping():
        raise RuntimeError("Redis недоступен — worker не может стартовать")

    ensure_collection()
    print("[celery_app] Worker готов.")