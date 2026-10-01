"""
Управление резюме:
  POST   /api/resumes/upload     — загрузка файла → enqueue → 202
  GET    /api/resumes            — список готовых резюме (Qdrant)
  GET    /api/resumes/{id}       — полное резюме
  DELETE /api/resumes/{id}       — удаление (Qdrant + Redis + файл)
"""

import os
import re
import unicodedata
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, File, HTTPException, UploadFile

from celery_client import enqueue_index_resume
from common.indexing import ResumeValidationError, validate_resume_content
from common.models import TaskStatus, TaskStatusValue
from common.qdrant_ops import (
    COLLECTION_NAME,
    delete_resume as qdrant_delete,
    get_client,
    get_resume as qdrant_get,
    list_resumes as qdrant_list,
)
from common.redis_ops import delete_task, save_task
from schemas import (
    ResumeDetail,
    ResumeListItem,
    ResumeListResponse,
    UploadResponse,
)


router = APIRouter(prefix="/api/resumes", tags=["resumes"])

RESUMES_DIR = os.getenv("RESUMES_DIR", "/app/resumes")
MAX_UPLOAD_BYTES = 512 * 1024  # 512 KB


# ─────────────────────────────────────────────────────────────
# Утилиты
# ─────────────────────────────────────────────────────────────

def _sanitize_filename(name: str) -> str:
    """
    Очищает имя файла от потенциально опасных символов.
    Гарантирует .txt-расширение.
    """
    name = os.path.basename(name)                       # отсекаем ../../etc/passwd
    name = unicodedata.normalize("NFKD", name)
    name = re.sub(r"[^\w\s\-.]", "", name, flags=re.UNICODE)
    name = re.sub(r"\s+", "_", name).strip("._")
    if not name.lower().endswith(".txt"):
        name = (name or "resume") + ".txt"
    return name


def _resolve_collision(path: str) -> str:
    """ivanov.txt → ivanov_2.txt → ivanov_3.txt ..."""
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    i = 2
    while os.path.exists(f"{base}_{i}{ext}"):
        i += 1
    return f"{base}_{i}{ext}"


# ─────────────────────────────────────────────────────────────
# POST /api/resumes/upload
# ─────────────────────────────────────────────────────────────

@router.post("/upload", response_model=UploadResponse, status_code=202)
async def upload_resume(file: UploadFile = File(...)):
    if not file.filename:
        raise HTTPException(400, "Не указано имя файла")

    # 1. Размер
    raw = await file.read()
    if len(raw) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            413,
            f"Файл слишком большой: {len(raw)} байт (лимит {MAX_UPLOAD_BYTES})",
        )

    # 2. Декодирование
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise HTTPException(422, "Файл должен быть в кодировке UTF-8")

    # 3. Санитизация имени
    safe_name = _sanitize_filename(file.filename)

    # 4. Валидация содержимого (до сохранения на диск)
    try:
        validate_resume_content(text, safe_name)
    except ResumeValidationError as e:
        raise HTTPException(422, str(e))

    # 5. Сохранение файла
    os.makedirs(RESUMES_DIR, exist_ok=True)
    final_path = _resolve_collision(os.path.join(RESUMES_DIR, safe_name))
    final_name = os.path.basename(final_path)
    with open(final_path, "w", encoding="utf-8") as f:
        f.write(text)

    # 6. Enqueue: сначала статус QUEUED, потом задача
    task_id = str(uuid.uuid4())
    save_task(TaskStatus(
        task_id=task_id,
        filename=final_name,
        status=TaskStatusValue.QUEUED,
        uploaded_at=datetime.now(timezone.utc).isoformat(),
    ))

    try:
        enqueue_index_resume(task_id, final_name)
    except Exception as e:
        # откат: удаляем запись из Redis, файл оставляем (подберёт CLI)
        delete_task(task_id)
        raise HTTPException(503, f"Не удалось поставить задачу в очередь: {e}")

    return UploadResponse(task_id=task_id, filename=final_name)


# ─────────────────────────────────────────────────────────────
# GET /api/resumes
# ─────────────────────────────────────────────────────────────

@router.get("", response_model=ResumeListResponse)
async def list_resumes(limit: int = 100):
    points, _ = qdrant_list(limit=limit)
    items = [
        ResumeListItem(
            id=str(p.id),
            file_name=p.payload.get("file_name", ""),
            title=p.payload.get("title"),
            salary=p.payload.get("salary"),
            format=p.payload.get("format"),
            skills_list=p.payload.get("skills_list", []),
            uploaded_at=p.payload.get("uploaded_at", ""),
            indexed_at=p.payload.get("indexed_at", ""),
        )
        for p in points
    ]
    return ResumeListResponse(items=items, total=len(items))


# ─────────────────────────────────────────────────────────────
# GET /api/resumes/{resume_id}
# ─────────────────────────────────────────────────────────────

@router.get("/{resume_id}", response_model=ResumeDetail)
async def get_resume(resume_id: str):
    payload = qdrant_get(resume_id)
    if payload is None:
        raise HTTPException(404, f"Резюме {resume_id} не найдено")
    return ResumeDetail(id=resume_id, **payload)


# ─────────────────────────────────────────────────────────────
# DELETE /api/resumes/{resume_id}
# ─────────────────────────────────────────────────────────────

@router.delete("/{resume_id}", status_code=204)
async def delete_resume(resume_id: str):
    payload = qdrant_get(resume_id)
    if payload is None:
        raise HTTPException(404, f"Резюме {resume_id} не найдено")

    qdrant_delete(resume_id)

    # Удаляем файл с диска (best-effort)
    file_name = payload.get("file_name")
    if file_name:
        file_path = os.path.join(RESUMES_DIR, file_name)
        try:
            if os.path.isfile(file_path):
                os.remove(file_path)
        except OSError as e:
            print(f"[resumes] Не удалось удалить файл {file_path}: {e}")

    # Удаляем возможную запись задачи из Redis (если осталась)
    # task_id != resume_id, поэтому чистим по совпадению resume_id
    from common.redis_ops import list_tasks
    for t in list_tasks():
        if t.resume_id == resume_id:
            delete_task(t.task_id)
            break