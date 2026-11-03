from __future__ import annotations

import re
import time
import unicodedata
from typing import Any

from .ai import AIProviderError, OllamaProvider
from .config import AppConfig
from .database import Database
from .tools import ToolRegistry
from .voice import SpeechToText, TextToSpeech, VoiceConfig, WakeWordDetector


class Assistant:
    def __init__(self, config: AppConfig, database: Database):
        if config.ai_provider != "ollama":
            raise ValueError(f"Proveedor de IA no compatible: {config.ai_provider}")
        self.config = config
        self.database = database
        self.tool_registry = ToolRegistry(database)
        self.ai_provider = OllamaProvider(
            config.ollama_url,
            config.model_name,
            keep_alive=config.ollama_keep_alive,
            num_predict=config.ollama_num_predict,
        )
        self.voice_config = VoiceConfig(wake_word=config.wake_word, language=config.language)
        self.wake_word_detector = WakeWordDetector(self.voice_config)
        self.stt = SpeechToText(self.voice_config)
        self.tts = TextToSpeech(self.voice_config)

    def handle_message(
        self,
        message: str,
        conversation_id: str = "default",
        client: str = "desktop",
    ) -> dict[str, Any]:
        started = time.perf_counter()
        text = (message or "").strip()
        if not text:
            return {"text": "No he detectado ninguna entrada.", "status": "idle", "actions": []}

        intent = self._infer_intent(text)
        if intent["action"] == "clarify_alarm":
            response = (
                "¿A qué hora quieres la alarma? Si dices una hora entre la 1 y las 12, "
                "aclara si es de la mañana o de la tarde."
            )
            self.database.add_chat_message(conversation_id, "user", text)
            self.database.add_chat_message(conversation_id, "assistant", response)
            return {"text": response, "status": "success", "actions": []}
        if intent["action"] == "set_alarm":
            if client != "android":
                response = "Para poner una alarma en el móvil, pídemelo desde la app Android."
                return {"text": response, "status": "success", "actions": []}
            action = {
                "type": "set_alarm",
                "hour": intent["hour"],
                "minute": intent["minute"],
                "label": intent["label"],
            }
            response = (
                f"Voy a poner la alarma a las {intent['hour']:02d}:"
                f"{intent['minute']:02d}."
            )
            self.database.add_chat_message(conversation_id, "user", text)
            self.database.add_chat_message(conversation_id, "assistant", response)
            return {
                "text": response,
                "status": "pending",
                "actions": [],
                "device_action": action,
            }
        if intent["action"] == "set_timer":
            if client != "android":
                response = "Para que el temporizador suene en el móvil, pídemelo desde la app Android."
                return {"text": response, "status": "success", "actions": []}
            seconds = intent["minutes"] * 60
            if seconds > 86_400:
                response = "El temporizador del móvil admite hasta 24 horas."
                return {"text": response, "status": "error", "actions": []}
            response = f"Voy a iniciar un temporizador de {intent['minutes']} minutos."
            self.database.add_chat_message(conversation_id, "user", text)
            self.database.add_chat_message(conversation_id, "assistant", response)
            return {
                "text": response,
                "status": "pending",
                "actions": [],
                "device_action": {
                    "type": "set_timer",
                    "seconds": seconds,
                    "label": "Sirvienta",
                },
            }
        actions = []
        tool_result = None

        if intent["action"] == "open_app":
            if client == "android":
                response = f"Voy a abrir {intent['target']} en el móvil."
                self.database.add_chat_message(conversation_id, "user", text)
                self.database.add_chat_message(conversation_id, "assistant", response)
                return {
                    "text": response,
                    "status": "pending",
                    "actions": [],
                    "device_action": {"type": "open_app", "name": intent["target"]},
                }
            tool_result = self.tool_registry.execute("open_app", {"name": intent["target"]})
        elif intent["action"] == "search_web":
            tool_result = self.tool_registry.execute("search_web", {"query": intent["target"]})
        elif intent["action"] == "time":
            tool_result = self.tool_registry.execute("get_time")
        elif intent["action"] == "date":
            tool_result = self.tool_registry.execute("get_date")
        elif intent["action"] == "set_reminder":
            tool_result = self.tool_registry.execute("set_reminder", {"title": intent["target"], "due_at": intent["due_at"]})
        elif intent["action"] == "set_timer":
            tool_result = self.tool_registry.execute("set_timer", {"minutes": intent["minutes"]})
        elif intent["action"] == "write_memory":
            tool_result = self.tool_registry.execute("write_memory", {"key": intent["key"], "value": intent["value"]})
        elif intent["action"] == "read_memory":
            tool_result = self.tool_registry.execute("read_memory", {"key": intent["key"]})
        elif intent["action"] == "add_note":
            tool_result = self.tool_registry.execute("add_note", {"content": intent["content"]})
        elif intent["action"] == "read_notes":
            tool_result = self.tool_registry.execute("read_notes")
        elif intent["action"] == "status":
            tool_result = self.tool_registry.execute("list_automations")
        else:
            history = self.database.chat_history(conversation_id, limit=8)
            memories = self.database.list_memories() if self.config.memory_enabled else []
            notes = self.database.list_notes(limit=10) if self.config.memory_enabled else []
            if notes:
                memories.append({
                    "key": "notas personales",
                    "value": [note["content"] for note in notes],
                })
            try:
                response = self.ai_provider.reply(history, text, memories)
            except AIProviderError as error:
                self.database.add_activity_log(
                    text,
                    "chat",
                    {"conversation_id": conversation_id},
                    str(error),
                    time.perf_counter() - started,
                    "ai_provider_error",
                )
                return {
                    "text": str(error),
                    "status": "error",
                    "actions": actions,
                }
            self.database.add_chat_message(conversation_id, "user", text)
            self.database.add_chat_message(conversation_id, "assistant", response)
            self.database.add_activity_log(
                text,
                "chat",
                {"conversation_id": conversation_id},
                response,
                time.perf_counter() - started,
                None,
            )
            return {"text": response, "status": "success", "actions": actions}

        execution_time = time.perf_counter() - started
        response = self._compose_response(intent, tool_result)
        self.database.add_chat_message(conversation_id, "user", text)
        self.database.add_chat_message(conversation_id, "assistant", response)
        actions.append({"tool": intent["action"], "ok": tool_result.ok, "result": tool_result.message})
        self.database.add_activity_log(text, intent["action"], {"target": intent.get("target")}, tool_result.message, execution_time, None if tool_result.ok else "tool_failed")
        return {"text": response, "status": "success" if tool_result.ok else "error", "actions": actions, "risk": tool_result.risk.value}

    def _infer_intent(self, text: str) -> dict[str, Any]:
        normalized = text.lower().strip()
        unaccented = "".join(
            character for character in unicodedata.normalize("NFKD", normalized)
            if not unicodedata.combining(character)
        )
        if re.search(
            r"\b(?:pon(?:me)?|programa(?:me)?|crea|activa|establece)\s+(?:una\s+)?alarma\b",
            unaccented,
        ):
            time_match = re.search(
                r"\b(?:a\s+las?|para\s+las?)\s+(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|de\s+la\s+manana|de\s+la\s+tarde|de\s+la\s+noche)?\b",
                unaccented,
            )
            if not time_match:
                return {"action": "clarify_alarm"}
            hour = int(time_match.group(1))
            minute = int(time_match.group(2) or "0")
            period = time_match.group(3)
            if minute > 59 or hour > (12 if period else 23):
                return {"action": "clarify_alarm"}
            if 1 <= hour <= 12 and not period:
                return {"action": "clarify_alarm"}
            if period in {"pm", "de la tarde"}:
                hour = hour % 12 + 12
            elif period == "de la noche":
                hour = hour % 12 if hour == 12 else hour + 12
            elif period in {"am", "de la manana"}:
                hour = hour % 12 if period == "am" else hour
            return {
                "action": "set_alarm",
                "hour": hour,
                "minute": minute,
                "label": "Sirvienta",
            }
        if re.search(
            r"\b(?:qué hora es|que hora es|dime la hora|hora actual|a qué hora)\b",
            normalized,
        ):
            return {"action": "time"}
        if re.search(
            r"\b(?:qué fecha es|que fecha es|qué día es|que dia es|fecha de hoy)\b",
            normalized,
        ):
            return {"action": "date"}
        if re.match(
            r"^(?:por favor,?\s*)?(?:apunta|anota|guarda(?:me)?\s+(?:una\s+)?nota|escribe\s+una\s+nota)\s+(?:que\s+)?(.+)$",
            text,
            re.IGNORECASE,
        ):
            note = re.sub(
                r"^(?:por favor,?\s*)?(?:apunta|anota|guarda(?:me)?\s+(?:una\s+)?nota|escribe\s+una\s+nota)\s+(?:que\s+)?",
                "",
                text,
                flags=re.IGNORECASE,
            ).strip()
            return {"action": "add_note", "content": note}
        if normalized.startswith("recuerda que "):
            return {"action": "add_note", "content": text[len("recuerda que "):].strip()}
        if re.search(
            r"\b(?:que apunte|que he apuntado|que tengo apuntado|lee mis notas|dime mis notas)\b",
            unaccented,
        ):
            return {"action": "read_notes"}
        open_match = re.match(
            r"^(?:por favor,?\s*)?(?:abre|abrir)\s+(.+)$",
            text,
            re.IGNORECASE,
        )
        if open_match:
            return {"action": "open_app", "target": open_match.group(1).strip()}
        search_match = re.match(
            r"^(?:por favor,?\s*)?(?:busca|buscar)\s+(.+)$",
            text,
            re.IGNORECASE,
        )
        if search_match:
            return {"action": "search_web", "target": search_match.group(1).strip()}
        if normalized.startswith(("recuérdame ", "recuerdame ", "crea un recordatorio ", "pon un recordatorio ")):
            title = text.split(" ", 1)[1].strip() or "Recordatorio"
            return {"action": "set_reminder", "target": title, "due_at": "mañana"}
        if normalized.startswith(("pon un temporizador", "inicia un temporizador", "temporizador")):
            duration = re.search(
                r"\b(\d+)\s*(minutos?|mins?|horas?|hrs?)\b",
                normalized,
            )
            if duration:
                minutes = int(duration.group(1))
                if duration.group(2).startswith(("hora", "hr")):
                    minutes *= 60
                return {"action": "set_timer", "minutes": minutes}
        if normalized.startswith(("guarda en memoria ", "recuerda que ")):
            return {"action": "write_memory", "key": "last_user_note", "value": text}
        if re.match(
            r"^[¿¡\s]*(?:qué recuerdas de mí|que recuerdas de mi|lee mi memoria)\b",
            normalized,
        ):
            return {"action": "read_memory", "key": "last_user_note"}
        if normalized in {"estado", "dime el estado", "estado del sistema", "status"}:
            return {"action": "status"}
        return {"action": "chat", "target": text}

    def _compose_response(self, intent: dict[str, Any], tool_result) -> str:
        if tool_result and tool_result.ok:
            if intent["action"] == "time":
                return f"La hora actual es {tool_result.message}."
            if intent["action"] == "date":
                return f"Hoy es {tool_result.message}."
            if intent["action"] == "open_app":
                return tool_result.message
            if intent["action"] == "read_memory":
                return f"Recuerdo: {tool_result.data['value']}"
            if intent["action"] == "read_notes":
                notes = tool_result.data.get("notes", [])
                return "Tus notas: " + "; ".join(note["content"] for note in notes[:10])
            if intent["action"] == "status":
                automations = tool_result.data.get("automations", [])
                if not automations:
                    return "El sistema está listo. Aún no hay automatizaciones guardadas."
                names = ", ".join(item["name"] for item in automations)
                return f"El sistema está listo. Automatizaciones: {names}."
            return tool_result.message
        if intent["action"] == "chat":
            return "He entendido tu mensaje, pero aún no tengo una tarea concreta asignada para ello."
        return tool_result.message if tool_result else "No he podido completar la acción."

    def _default_reply(self, text: str) -> str:
        if "hola" in text.lower():
            return "Hola, estoy listo para ayudarte."
        if "gracias" in text.lower():
            return "De nada. Estoy aquí para ayudarte."
        return "Lo tengo en cuenta; necesito más contexto para ejecutar la acción correcta."

    def is_wake_word(self, text: str) -> bool:
        return self.wake_word_detector.detect(text)

    def speak(self, text: str) -> None:
        self.tts.speak(text)
