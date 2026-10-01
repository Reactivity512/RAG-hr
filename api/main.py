"""
Точка входа FastAPI.

Ответственности:
- Создание app
- Startup: проверка Redis, ensure_collection в Qdrant
- Подключение роутеров (search, resumes, tasks)
- Раздача статики (chat / upload / resumes)
"""

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from common.qdrant_ops import ensure_collection
from common.redis_ops import ping as redis_ping
from routes.resumes import router as resumes_router
from routes.search import router as search_router
from routes.tasks import router as tasks_router


app = FastAPI(title="RAG Resume API", version="0.2.0")


# ─────────────────────────────────────────────────────────────
# Startup
# ─────────────────────────────────────────────────────────────

@app.on_event("startup")
async def on_startup():
    print("[main] Запуск API...")

    if not redis_ping():
        # Fail fast: без Redis Celery-задачи не работают,
        raise RuntimeError("Redis недоступен — API не может стартовать")

    ensure_collection()

    print("[main] API готов.")


# ─────────────────────────────────────────────────────────────
# Healthcheck
# ─────────────────────────────────────────────────────────────

@app.get("/health", tags=["system"])
async def health():
    return {"status": "ok"}


# ─────────────────────────────────────────────────────────────
# Роутеры (регистрируем ДО монтирования static — иначе mount
# на "/" перехватит /api/* и /search)
# ─────────────────────────────────────────────────────────────

app.include_router(search_router)
app.include_router(resumes_router)
app.include_router(tasks_router)


# ─────────────────────────────────────────────────────────────
# Страницы (HTML)
# ─────────────────────────────────────────────────────────────

@app.get("/", include_in_schema=False)
async def page_chat():
    return FileResponse("static/index.html")


@app.get("/upload", include_in_schema=False)
async def page_upload():
    return FileResponse("static/upload.html")


# ─────────────────────────────────────────────────────────────
# Статика (CSS, JS — монтируется ПОСЛЕ роутеров)
# ─────────────────────────────────────────────────────────────

app.mount("/static", StaticFiles(directory="static"), name="static")