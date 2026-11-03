from __future__ import annotations

import os
import shutil
import subprocess
import time
import unicodedata
import webbrowser
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from .database import Database


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass
class ToolResult:
    ok: bool
    message: str
    data: dict[str, Any] = field(default_factory=dict)
    risk: RiskLevel = RiskLevel.LOW


class ToolRegistry:
    def __init__(self, database: Database):
        self.database = database

    def execute(self, action: str, params: dict[str, Any] | None = None, user_context: dict[str, Any] | None = None) -> ToolResult:
        params = params or {}
        user_context = user_context or {}

        if action == "open_app":
            return self._open_app(params.get("name"), params.get("path"))
        if action == "close_app":
            return self._close_app(params.get("name"))
        if action == "search_web":
            return self._search_web(params.get("query"))
        if action == "get_time":
            return self._get_time()
        if action == "get_date":
            return self._get_date()
        if action == "set_reminder":
            return self._set_reminder(params.get("title"), params.get("description"), params.get("due_at"))
        if action == "set_timer":
            return self._set_timer(params.get("minutes"))
        if action == "read_memory":
            return self._read_memory(params.get("key"))
        if action == "write_memory":
            return self._write_memory(params.get("key"), params.get("value"))
        if action == "add_note":
            return self._add_note(params.get("content"))
        if action == "read_notes":
            return self._read_notes()
        if action == "run_automation":
            return self._run_automation(params.get("name"))
        if action == "list_automations":
            return self._list_automations()
        return ToolResult(False, "Herramient no reconocida", {}, RiskLevel.LOW)

    def _open_app(self, name: str | None, path: str | None) -> ToolResult:
        if not name and not path:
            return ToolResult(False, "No se indicó la aplicación a abrir.", {}, RiskLevel.LOW)
        target = path or name
        try:
            if os.name == "nt":
                if path:
                    subprocess.Popen([path])
                else:
                    app_name = self._normalize_app_name(name)
                    if app_name in {"navegador", "browser", "internet"}:
                        if not webbrowser.open("about:blank"):
                            raise OSError("No hay un navegador predeterminado disponible.")
                    else:
                        executable = self._WINDOWS_APP_ALIASES.get(app_name, app_name)
                        resolved = shutil.which(executable)
                        if not resolved:
                            system_executable = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / executable
                            if system_executable.is_file():
                                resolved = str(system_executable)
                        if resolved:
                            subprocess.Popen([resolved])
                        else:
                            shortcut_name = self._WINDOWS_APP_SHORTCUT_ALIASES.get(app_name, app_name)
                            shortcut = self._find_start_menu_app(shortcut_name)
                            if not shortcut:
                                return ToolResult(
                                    False,
                                    f"No encuentro «{name}». Prueba con Calculadora, Bloc de notas, Explorador, Chrome o Edge.",
                                    {"target": target},
                                    RiskLevel.LOW,
                                )
                            os.startfile(str(shortcut))
            else:
                subprocess.Popen(["xdg-open", target])
            return ToolResult(True, f"Abriendo {target}.", {"target": target}, RiskLevel.LOW)
        except Exception as exc:  # pragma: no cover - OS-specific path
            return ToolResult(False, f"No he podido abrir {target}: {exc}", {"target": target}, RiskLevel.LOW)

    _WINDOWS_APP_ALIASES = {
        "calculadora": "calc.exe",
        "calculator": "calc.exe",
        "bloc de notas": "notepad.exe",
        "notepad": "notepad.exe",
        "explorador": "explorer.exe",
        "explorador de archivos": "explorer.exe",
        "paint": "mspaint.exe",
        "pintura": "mspaint.exe",
        "chrome": "chrome.exe",
        "google chrome": "chrome.exe",
        "edge": "msedge.exe",
        "microsoft edge": "msedge.exe",
        "firefox": "firefox.exe",
        "visual studio code": "code.exe",
        "vs code": "code.exe",
        "vscode": "code.exe",
        "spotify": "spotify.exe",
    }
    _WINDOWS_APP_SHORTCUT_ALIASES = {
        "chrome": "google chrome",
        "google chrome": "google chrome",
        "edge": "microsoft edge",
        "visual studio code": "visual studio code",
        "vs code": "visual studio code",
        "vscode": "visual studio code",
    }

    @staticmethod
    def _normalize_app_name(name: str) -> str:
        normalized = unicodedata.normalize("NFKD", name.casefold())
        normalized = "".join(character for character in normalized if not unicodedata.combining(character))
        normalized = " ".join(normalized.strip().split())
        for article in ("el ", "la ", "los ", "las ", "un ", "una "):
            if normalized.startswith(article):
                normalized = normalized[len(article):]
                break
        return normalized.removesuffix(".exe").strip()

    @classmethod
    def _find_start_menu_app(cls, app_name: str) -> Path | None:
        start_menu_roots = (
            Path(os.environ.get("APPDATA", "")) / "Microsoft" / "Windows" / "Start Menu" / "Programs",
            Path(os.environ.get("PROGRAMDATA", "")) / "Microsoft" / "Windows" / "Start Menu" / "Programs",
        )
        for root in start_menu_roots:
            if not root.is_dir():
                continue
            for shortcut in root.rglob("*"):
                if shortcut.suffix.casefold() not in {".lnk", ".url"}:
                    continue
                if cls._normalize_app_name(shortcut.stem) == app_name:
                    return shortcut
        return None

    def _close_app(self, name: str | None) -> ToolResult:
        if not name:
            return ToolResult(False, "No se indicó la aplicación a cerrar.", {}, RiskLevel.LOW)
        trying = "" if os.name == "nt" else ""
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/IM", f"{name}.exe"], check=False, capture_output=True, text=True)
            else:
                subprocess.run(["pkill", "-f", name], check=False)
            return ToolResult(True, f"He intentado cerrar {name}.", {"target": name}, RiskLevel.MEDIUM)
        except Exception as exc:
            return ToolResult(False, f"No he podido cerrar {name}: {exc}", {"target": name}, RiskLevel.MEDIUM)

    def _search_web(self, query: str | None) -> ToolResult:
        if not query:
            return ToolResult(False, "No se indicó una búsqueda.", {}, RiskLevel.LOW)
        url = f"https://www.google.com/search?q={query.replace(' ', '+')}"
        try:
            webbrowser.open(url)
            return ToolResult(True, f"Buscando en la web: {query}", {"query": query, "url": url}, RiskLevel.LOW)
        except Exception as exc:
            return ToolResult(False, f"No pude abrir la búsqueda: {exc}", {"query": query}, RiskLevel.LOW)

    def _get_time(self) -> ToolResult:
        from datetime import datetime

        now = datetime.now()
        return ToolResult(True, now.strftime("%H:%M"), {"time": now.strftime("%H:%M")}, RiskLevel.LOW)

    def _get_date(self) -> ToolResult:
        from datetime import datetime

        now = datetime.now()
        return ToolResult(True, now.strftime("%A %d de %B de %Y"), {"date": now.strftime("%Y-%m-%d")}, RiskLevel.LOW)

    def _set_reminder(self, title: str | None, description: str | None, due_at: str | None) -> ToolResult:
        if not title or not due_at:
            return ToolResult(False, "Hace falta un título y una fecha para crear el recordatorio.", {}, RiskLevel.MEDIUM)
        reminder_id = self.database.add_reminder(title, description or "", due_at)
        return ToolResult(True, f"Recordatorio creado con id {reminder_id}.", {"id": reminder_id, "title": title, "due_at": due_at}, RiskLevel.MEDIUM)

    def _set_timer(self, minutes: int | str | None) -> ToolResult:
        try:
            minutes = int(minutes)
        except (TypeError, ValueError):
            return ToolResult(False, "El temporizador necesita un número válido de minutos.", {}, RiskLevel.MEDIUM)
        if minutes <= 0:
            return ToolResult(False, "La duración del temporizador debe ser mayor que cero.", {}, RiskLevel.MEDIUM)
        start = time.time()
        return ToolResult(True, f"Temporizador de {minutes} minutos activado.", {"minutes": minutes, "started_at": start}, RiskLevel.MEDIUM)

    def _read_memory(self, key: str | None) -> ToolResult:
        if not key:
            return ToolResult(False, "No se indicó una clave de memoria.", {}, RiskLevel.LOW)
        value = self.database.get_memory(key)
        if value is None:
            return ToolResult(False, f"No existe la memoria '{key}'.", {"key": key}, RiskLevel.LOW)
        return ToolResult(True, f"Memoria '{key}' recuperada.", {"key": key, "value": value}, RiskLevel.LOW)

    def _write_memory(self, key: str | None, value: Any) -> ToolResult:
        if not key:
            return ToolResult(False, "Hace falta una clave para guardar la memoria.", {}, RiskLevel.MEDIUM)
        self.database.save_memory(key, value)
        return ToolResult(True, f"Memoria '{key}' guardada.", {"key": key, "value": value}, RiskLevel.MEDIUM)

    def _add_note(self, content: str | None) -> ToolResult:
        if not content or not content.strip():
            return ToolResult(False, "La nota está vacía.", {}, RiskLevel.MEDIUM)
        note_id = self.database.add_note(content.strip())
        return ToolResult(
            True,
            "He apuntado la nota.",
            {"id": note_id, "content": content.strip()},
            RiskLevel.MEDIUM,
        )

    def _read_notes(self) -> ToolResult:
        notes = self.database.list_notes()
        if not notes:
            return ToolResult(False, "Todavía no tienes notas guardadas.", {"notes": []}, RiskLevel.LOW)
        return ToolResult(True, "Notas recuperadas.", {"notes": notes}, RiskLevel.LOW)

    def _run_automation(self, name: str | None) -> ToolResult:
        if not name:
            return ToolResult(False, "No se indicó la automatización.", {}, RiskLevel.MEDIUM)
        for automation in self.database.list_automations():
            if automation["name"].lower() == name.lower():
                actions = automation["actions"]
                return ToolResult(True, f"Automatización '{name}' ejecutada.", {"name": name, "actions": actions}, RiskLevel.MEDIUM)
        return ToolResult(False, f"No existe la automatización '{name}'.", {"name": name}, RiskLevel.MEDIUM)

    def _list_automations(self) -> ToolResult:
        automations = self.database.list_automations()
        return ToolResult(True, "Automatizaciones cargadas.", {"automations": automations}, RiskLevel.LOW)
