"""
Парсинг резюме и валидация соответствия шаблону.

Шаблон .txt файла (обязательные секции помечены *):
    Название специальности: *
    ...
    Заработная плата:
    ...
    Формат работы:
    ...
    Контакты:
    ...
    Опыт работы: *
    ...
    Навыки: *
    ...
    Образование:
    ...
    Об себе:
    ...
"""

import re


# ─────────────────────────────────────────────────────────────
# Обязательные секции для индексации
# ─────────────────────────────────────────────────────────────

REQUIRED_SECTIONS = {
    "title": "Название специальности",
    "experience_text": "Опыт работы",
}

# skills_list проверяется отдельно (это список, а не строка)


# ─────────────────────────────────────────────────────────────
# Парсинг
# ─────────────────────────────────────────────────────────────

def parse_resume_txt(text: str, file_name: str) -> dict:
    """
    Парсит TXT-резюме по шаблону и извлекает структурированные данные.
    """
    # Нормализация: BOM (Windows Notepad) + переводы строк (\r\n, \r → \n)
    text = text.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")

    print(f"[parsing] file={file_name}, first120={text[:120]!r}")
    print(f"[parsing] has_title_marker={'Название специальности:' in text}")
    print(f"[parsing] has_skills_marker={'Навыки:' in text}")

    payload = {
        "file_name": file_name,
        "title": None,
        "salary": None,
        "format": None,
        "contacts": None,
        "experience_text": None,
        "skills_list": [],
        "education": None,
        "about": None,
        "full_text": text,
    }

    patterns = {
        "title": r"Название специальности:\s*(.*?)\n\n",
        "salary": r"Заработная плата:\s*(.*?)\n\n",
        "format": r"Формат работы:\s*(.*?)\n\n",
        "contacts": r"Контакты:\s*(.*?)\n\n",
        "experience_text": r"Опыт работы:\s*(.*?)\n\nНавыки:",
        "education": r"Образование:\s*(.*?)\n\n",
        # Три варианта секции «о кандидате» — non-capturing группа для варианта
        "about": r"(?:О себе|Обо мне|Об себе):\s*(.*)",
    }

    for key, pattern in patterns.items():
        match = re.search(pattern, text, re.DOTALL)
        if match:
            payload[key] = match.group(1).strip()

    # Навыки: вытащить чистые названия без уровня в скобках
    skills_match = re.search(r"Навыки:\s*(.*?)\n\nОбразование:", text, re.DOTALL)
    if skills_match:
        raw_skills = skills_match.group(1).strip().split("\n")
        clean_skills = [
            re.sub(r"\s*\(.*?\)", "", skill).strip()
            for skill in raw_skills
            if skill.strip()
        ]
        payload["skills_list"] = clean_skills

    return payload


# ─────────────────────────────────────────────────────────────
# Валидация
# ─────────────────────────────────────────────────────────────

def validate_resume(payload: dict) -> list[str]:
    """
    Проверяет, что все обязательные секции резюме заполнены.

    Возвращает список отсутствующих секций (человекочитаемые названия).
    Пустой список = резюме валидно и может быть проиндексировано.
    """
    missing = []

    for key, human_name in REQUIRED_SECTIONS.items():
        if not payload.get(key):
            missing.append(human_name)

    if not payload.get("skills_list"):
        missing.append("Навыки")

    return missing