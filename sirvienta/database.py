from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Generator


class Database:
    def __init__(self, db_path: str):
        self.db_path = db_path
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    @contextmanager
    def _connect(self) -> Generator[sqlite3.Connection, None, None]:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def _initialize(self) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT UNIQUE,
                    value TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS reminders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    description TEXT,
                    due_at TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    created_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS automations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    trigger TEXT NOT NULL,
                    actions TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS activity_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    user_intent TEXT,
                    tool TEXT,
                    parameters TEXT,
                    result TEXT,
                    execution_time REAL,
                    error TEXT
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS preferences (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation ON chat_messages (conversation_id, id)"
            )

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def set_preference(self, key: str, value: Any) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO preferences (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                (key, json.dumps(value), self._now()),
            )

    def get_preference(self, key: str, default: Any = None) -> Any:
        with self._connect() as conn:
            row = conn.execute("SELECT value FROM preferences WHERE key = ?", (key,)).fetchone()
            if row is None:
                return default
            return json.loads(row["value"])

    def chat_history(self, conversation_id: str, limit: int = 20) -> list[dict[str, str]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT role, content FROM (
                    SELECT id, role, content FROM chat_messages
                    WHERE conversation_id = ?
                    ORDER BY id DESC LIMIT ?
                ) ORDER BY id ASC
                """,
                (conversation_id, limit),
            ).fetchall()
            return [{"role": row["role"], "content": row["content"]} for row in rows]

    def add_chat_message(self, conversation_id: str, role: str, content: str) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO chat_messages (conversation_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                (conversation_id, role, content, self._now()),
            )

    def save_memory(self, key: str, value: Any) -> None:
        now = self._now()
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO memories (key, value, created_at, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                (key, json.dumps(value), now, now),
            )

    def get_memory(self, key: str, default: Any = None) -> Any:
        with self._connect() as conn:
            row = conn.execute("SELECT value FROM memories WHERE key = ?", (key,)).fetchone()
            if row is None:
                return default
            return json.loads(row["value"])

    def list_memories(self) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute("SELECT key, value, created_at, updated_at FROM memories ORDER BY updated_at DESC").fetchall()
            return [
                {"key": row["key"], "value": json.loads(row["value"]), "created_at": row["created_at"], "updated_at": row["updated_at"]}
                for row in rows
            ]

    def add_note(self, content: str) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO notes (content, created_at) VALUES (?, ?)",
                (content, self._now()),
            )
            return int(cursor.lastrowid)

    def list_notes(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id, content, created_at FROM notes ORDER BY id DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [
                {"id": row["id"], "content": row["content"], "created_at": row["created_at"]}
                for row in rows
            ]

    def add_activity_log(self, user_intent: str, tool: str, parameters: Any, result: str, execution_time: float, error: str | None = None) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO activity_logs (timestamp, user_intent, tool, parameters, result, execution_time, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (
                    self._now(),
                    user_intent,
                    tool,
                    json.dumps(parameters),
                    result,
                    execution_time,
                    error,
                ),
            )

    def recent_activity(self, limit: int = 20) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT timestamp, user_intent, tool, parameters, result, execution_time, error FROM activity_logs ORDER BY id DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [
                {
                    "timestamp": row["timestamp"],
                    "user_intent": row["user_intent"],
                    "tool": row["tool"],
                    "parameters": json.loads(row["parameters"]) if row["parameters"] else {},
                    "result": row["result"],
                    "execution_time": row["execution_time"],
                    "error": row["error"],
                }
                for row in rows
            ]

    def add_reminder(self, title: str, description: str, due_at: str) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO reminders (title, description, due_at, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
                (title, description, due_at, self._now()),
            )
            return int(cursor.lastrowid)

    def list_reminders(self) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute("SELECT id, title, description, due_at, status, created_at FROM reminders ORDER BY due_at ASC").fetchall()
            return [dict(row) for row in rows]

    def add_automation(self, name: str, trigger: str, actions: list[str]) -> int:
        with self._connect() as conn:
            cursor = conn.execute(
                "INSERT INTO automations (name, trigger, actions, enabled, created_at) VALUES (?, ?, ?, 1, ?)",
                (name, trigger, json.dumps(actions), self._now()),
            )
            return int(cursor.lastrowid)

    def list_automations(self) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute("SELECT id, name, trigger, actions, enabled, created_at FROM automations ORDER BY created_at DESC").fetchall()
            return [
                {
                    "id": row["id"],
                    "name": row["name"],
                    "trigger": row["trigger"],
                    "actions": json.loads(row["actions"]),
                    "enabled": bool(row["enabled"]),
                    "created_at": row["created_at"],
                }
                for row in rows
            ]
