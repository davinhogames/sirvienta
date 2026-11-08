from __future__ import annotations

import json
import logging
from pathlib import Path

import aiohttp
from edge_tts.exceptions import EdgeTTSException
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.concurrency import run_in_threadpool

from .assistant import Assistant
from .config import get_config
from .database import Database
from .speech import synthesize_speech
from .voice import VoiceConversation, VoiceError

logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    config = get_config()
    database = Database(config.database_path)
    assistant = Assistant(config, database)
    app = FastAPI(title=config.project_name, version="0.1.0")

    web_root = Path(__file__).resolve().parent.parent / "web"
    android_apk = (
        Path(__file__).resolve().parent.parent
        / "android"
        / "app"
        / "build"
        / "outputs"
        / "apk"
        / "debug"
        / "app-debug.apk"
    )
    app.mount("/static", StaticFiles(directory=str(web_root)), name="static")

    def android_release_metadata() -> dict:
        metadata_path = android_apk.with_name("release.json")
        metadata = {}
        if metadata_path.is_file():
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        apk_exists = android_apk.is_file()
        apk_mtime_ms = android_apk.stat().st_mtime_ns // 1_000_000 if apk_exists else None
        apk_is_current = (
            apk_exists
            and metadata.get("apk_last_modified") == apk_mtime_ms
        )
        return {
            "available": apk_is_current,
            "version_code": metadata.get("version_code"),
            "version_name": metadata.get("version_name"),
            "size_bytes": android_apk.stat().st_size if apk_is_current else None,
            "kind": "debug",
            "architecture": "arm64-v8a",
            "download_url": "/downloads/android" if apk_is_current else None,
        }

    @app.get("/", response_class=HTMLResponse)
    async def index() -> str:
        return (web_root / "download.html").read_text(encoding="utf-8")

    @app.get("/app", response_class=HTMLResponse)
    async def web_app() -> str:
        return (web_root / "index.html").read_text(encoding="utf-8")

    @app.get("/descargar", response_class=HTMLResponse)
    async def downloads_page() -> str:
        return (web_root / "download.html").read_text(encoding="utf-8")

    @app.get("/api/downloads")
    async def downloads() -> dict:
        return {
            "android": android_release_metadata(),
            "iphone": {
                "available": True,
                "kind": "web_app",
                "url": "/",
            },
        }

    @app.get("/api/android-release")
    async def android_release() -> dict:
        return android_release_metadata()

    @app.get("/downloads/android", name="download_android")
    async def download_android() -> FileResponse:
        if not android_release_metadata()["available"]:
            raise HTTPException(status_code=404, detail="El APK de Android no está disponible o no está actualizado.")
        return FileResponse(
            android_apk,
            media_type="application/vnd.android.package-archive",
            filename="sirvienta-android-beta.apk",
        )

    @app.head("/downloads/android", name="head_download_android")
    async def head_download_android() -> Response:
        if not android_release_metadata()["available"]:
            raise HTTPException(status_code=404, detail="El APK de Android no está disponible o no está actualizado.")
        return Response(
            status_code=200,
            headers={
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Length": str(android_apk.stat().st_size),
                "Content-Disposition": 'attachment; filename="sirvienta-android-beta.apk"',
            },
        )

    @app.get("/api/health")
    async def health() -> dict:
        return {"status": "ok", "name": config.project_name}

    @app.get("/api/status")
    async def status() -> dict:
        return {
            "project": config.project_name,
            "wake_word": config.wake_word,
            "mode": "online",
            "memory_enabled": config.memory_enabled,
            "activity": database.recent_activity(limit=5),
        }

    @app.get("/api/speech")
    async def speech(text: str) -> Response:
        if not text.strip():
            raise HTTPException(status_code=422, detail="Falta el texto que se debe pronunciar.")
        if len(text) > 1200:
            raise HTTPException(status_code=413, detail="La respuesta de voz supera el tamaño permitido.")
        try:
            audio = await synthesize_speech(text.strip(), config.tts_voice)
        except (EdgeTTSException, aiohttp.ClientError, TimeoutError) as error:
            logger.exception("Falló la generación de voz neural.")
            raise HTTPException(
                status_code=502,
                detail="No se pudo generar la voz neural. Comprueba la conexión y vuelve a intentarlo.",
            ) from error
        return Response(
            content=audio,
            media_type="audio/mpeg",
            headers={"Cache-Control": "no-store"},
        )

    @app.post("/api/message")
    async def message(payload: dict) -> dict:
        text = payload.get("text", "")
        conversation_id = payload.get("conversation_id", "default")
        if not isinstance(text, str) or not text.strip():
            return {"error": "No se recibió texto."}
        if not isinstance(conversation_id, str) or not conversation_id.strip() or len(conversation_id) > 128:
            return {"error": "El identificador de conversación no es válido."}
        client = payload.get("client", "web")
        if not isinstance(client, str) or client not in {"web", "android"}:
            return {"error": "El cliente indicado no es válido."}
        result = assistant.handle_message(text, conversation_id.strip(), client)
        return result

    @app.post("/api/transcribe")
    async def transcribe(request: Request) -> dict:
        if not request.headers.get("content-type", "").startswith("audio/wav"):
            raise HTTPException(status_code=415, detail="Se esperaba audio WAV.")
        audio_data = bytearray()
        async for chunk in request.stream():
            audio_data.extend(chunk)
            if len(audio_data) > 512_000:
                raise HTTPException(status_code=413, detail="La grabación supera el tamaño permitido.")
        try:
            text = await run_in_threadpool(assistant.stt.transcribe_wav, bytes(audio_data))
        except VoiceError as error:
            raise HTTPException(status_code=error.status_code, detail=str(error)) from error
        if not text:
            raise HTTPException(status_code=422, detail="No he entendido voz en la grabación. Inténtalo de nuevo.")
        return {"text": text}

    @app.websocket("/api/voice")
    async def voice(websocket: WebSocket) -> None:
        conversation_id = websocket.query_params.get("conversation_id", "default")
        if not conversation_id.strip() or len(conversation_id) > 128:
            await websocket.close(code=1008, reason="Identificador de conversación no válido.")
            return
        await websocket.accept()
        try:
            recognizer = await run_in_threadpool(assistant.stt.create_streaming_recognizer)
            voice_conversation = VoiceConversation(config.wake_word)
            last_partial = ""
            await websocket.send_json({"type": "ready", "active": False})

            while True:
                audio_data = await websocket.receive_bytes()
                transcript, partial = await run_in_threadpool(
                    assistant.stt.accept_audio,
                    recognizer,
                    audio_data,
                )
                if partial and voice_conversation.active and partial != last_partial:
                    await websocket.send_json({"type": "partial", "text": partial})
                    last_partial = partial
                if not transcript:
                    continue

                last_partial = ""
                turn = voice_conversation.process(transcript)
                if not turn.activated and not turn.command and not turn.closed:
                    continue
                if turn.activated and not turn.command and not turn.closed:
                    await websocket.send_json({"type": "activated", "text": "Te escucho."})
                    continue

                messages = []
                if turn.command:
                    result = await run_in_threadpool(
                        assistant.handle_message,
                        turn.command,
                        conversation_id.strip(),
                        "web",
                    )
                    messages.append(result["text"])
                if turn.closed:
                    messages.append("Hasta luego.")
                if turn.command:
                    await websocket.send_json({
                        "type": "activity",
                        "activity": database.recent_activity(limit=5),
                    })
                await websocket.send_json({
                    "type": "response",
                    "text": " ".join(messages),
                    "active": voice_conversation.active,
                    "status": result["status"] if turn.command else "success",
                    "external_action": result.get("external_action") if turn.command else None,
                })
        except WebSocketDisconnect:
            return
        except VoiceError as error:
            try:
                await websocket.send_json({"type": "error", "text": str(error)})
                await websocket.close(code=1011)
            except WebSocketDisconnect:
                return

    @app.get("/api/conversations/{conversation_id}")
    async def conversation(conversation_id: str) -> dict:
        if not conversation_id.strip() or len(conversation_id) > 128:
            return {"error": "El identificador de conversación no es válido."}
        return {
            "conversation_id": conversation_id,
            "messages": database.chat_history(conversation_id.strip()),
        }

    @app.get("/api/memory")
    async def memory() -> dict:
        return {"memories": database.list_memories()}

    @app.post("/api/memory")
    async def save_memory(payload: dict) -> dict:
        key = payload.get("key")
        value = payload.get("value")
        if not key:
            return {"error": "Falta la clave de memoria."}
        database.save_memory(key, value)
        return {"ok": True, "key": key, "value": value}

    @app.get("/api/automations")
    async def automations() -> dict:
        return {"automations": database.list_automations()}

    @app.post("/api/automations")
    async def create_automation(payload: dict) -> dict:
        name = payload.get("name")
        trigger = payload.get("trigger")
        actions = payload.get("actions", [])
        if not name or not trigger:
            return {"error": "Necesitas nombre y trigger."}
        automation_id = database.add_automation(name, trigger, actions)
        return {"ok": True, "id": automation_id}

    return app
