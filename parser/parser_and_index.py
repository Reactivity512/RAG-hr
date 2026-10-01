"""
CLI для полной переиндексации всех резюме из RESUMES_DIR.

Запуск:
    docker compose --profile manual up parser-cli
    # или локально: python parser_and_index.py

Логика:
    1. Пересоздаём коллекцию Qdrant (drop + create) — чтобы не было дублей.
    2. Читаем все .txt в RESUMES_DIR.
    3. Для каждого: парсинг → валидация → эмбеддинг → upsert.
    4. Печатаем summary. Exit code 0 если всё ок, 2 если были ошибки.

НЕ пишет статусы в Redis — это offline-инструмент, не связан с UI.
"""

import os
import sys

from common.indexing import ResumeValidationError, process_resume_to_item
from common.qdrant_ops import recreate_collection, upsert_batch


RESUMES_DIR = os.getenv("RESUMES_DIR", "./resumes")


def main() -> int:
    print(f"[cli] Полная переиндексация из: {RESUMES_DIR}")

    if not os.path.isdir(RESUMES_DIR):
        print(f"[cli] ОШИБКА: папка не найдена: {RESUMES_DIR}")
        return 1

    txt_files = sorted(f for f in os.listdir(RESUMES_DIR) if f.endswith(".txt"))
    if not txt_files:
        print("[cli] В папке нет .txt файлов — нечего индексировать.")
        return 0

    print(f"[cli] Найдено файлов: {len(txt_files)}")
    print("[cli] Пересоздаю коллекцию Qdrant (drop + create)...")
    recreate_collection()

    ok = 0
    failed: list[tuple[str, str]] = []

    items = []
    for i, file_name in enumerate(txt_files, 1):
        file_path = os.path.join(RESUMES_DIR, file_name)
        print(f"[cli] [{i}/{len(txt_files)}] {file_name}")
        try:
            item = process_resume_to_item(file_path, file_name)
            items.append(item)
            ok += 1
            print(f"[cli]   OK → {item[0]}")
        except ResumeValidationError as e:
            print(f"[cli]   SKIP (валидация): {e}")
            failed.append((file_name, str(e)))
        except Exception as e:
            print(f"[cli]   FAIL: {type(e).__name__}: {e}")
            failed.append((file_name, f"{type(e).__name__}: {e}"))

    # Батчевая загрузка в Qdrant одним вызовом
    if items:
        print(f"[cli] Загружаю {len(items)} точек в Qdrant...")
        upsert_batch(items)

    print()
    print(f"[cli] ──────────────────────────────────────────")
    print(f"[cli] Итог: успешно {ok}, ошибок {len(failed)}")
    if failed:
        print("[cli] Проблемные файлы:")
        for name, err in failed:
            print(f"[cli]   • {name}: {err}")

    return 0 if not failed else 2


if __name__ == "__main__":
    sys.exit(main())