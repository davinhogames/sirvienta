from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


@dataclass
class AppConfig:
    project_name: str = "SIRVIENTA"
    wake_word: str = "Sirvienta"
    ai_provider: str = "ollama"
    model_name: str = "qwen2.5:3b"
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_keep_alive: str = "10m"
    ollama_num_predict: int = 120
    tts_voice: str = "es-ES-ElviraNeural"
    language: str = "es-ES"
    app_port: int = 8000
    database_path: str = "data/sirvienta.db"
    log_level: str = "INFO"
    memory_enabled: bool = True
    wake_word_enabled: bool = True


def get_config() -> AppConfig:
    load_dotenv(dotenv_path=Path(__file__).resolve().parent.parent / ".env", override=False)
    db_path = os.getenv("DATABASE_PATH", "data/sirvienta.db")
    project_root = Path(__file__).resolve().parent.parent
    db_path = str((project_root / db_path).resolve()) if not Path(db_path).is_absolute() else db_path
    ai_provider = os.getenv("AI_PROVIDER", "ollama").lower()
    if ai_provider == "local":
        ai_provider = "ollama"

    return AppConfig(
        project_name=os.getenv("PROJECT_NAME", "SIRVIENTA"),
        wake_word=os.getenv("WAKE_WORD", "Sirvienta"),
        ai_provider=ai_provider,
        model_name=os.getenv("MODEL_NAME", "qwen2.5:3b"),
        ollama_url=os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/"),
        ollama_keep_alive=os.getenv("OLLAMA_KEEP_ALIVE", "10m"),
        ollama_num_predict=int(os.getenv("OLLAMA_NUM_PREDICT", "120")),
        tts_voice=os.getenv("TTS_VOICE", "es-ES-ElviraNeural"),
        language=os.getenv("LANGUAGE", "es-ES"),
        app_port=int(os.getenv("APP_PORT", "8000")),
        database_path=db_path,
        log_level=os.getenv("LOG_LEVEL", "INFO"),
        memory_enabled=os.getenv("MEMORY_ENABLED", "true").lower() == "true",
        wake_word_enabled=os.getenv("WAKE_WORD_ENABLED", "true").lower() == "true",
    )
