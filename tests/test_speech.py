from __future__ import annotations

import tempfile
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

from sirvienta.app import create_app
from sirvienta.config import AppConfig
from sirvienta.speech import synthesize_speech


class SpeechSynthesisTests(unittest.IsolatedAsyncioTestCase):
    async def test_synthesizer_returns_only_audio_chunks(self) -> None:
        class FakeCommunicate:
            def __init__(self, text: str, voice: str) -> None:
                self.text = text
                self.voice = voice

            async def stream(self):
                yield {"type": "audio", "data": b"first"}
                yield {"type": "WordBoundary", "data": b"ignored"}
                yield {"type": "audio", "data": b"second"}

        with patch("sirvienta.speech.Communicate", FakeCommunicate):
            audio = await synthesize_speech("Hola", "es-ES-ElviraNeural")

        self.assertEqual(audio, b"firstsecond")


class SpeechEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_speech_endpoint_returns_mp3_and_uses_configured_voice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = AppConfig(database_path=f"{directory}/speech.db", tts_voice="es-ES-ElviraNeural")
            with (
                patch("sirvienta.app.get_config", return_value=config),
                patch("sirvienta.app.synthesize_speech", new=AsyncMock(return_value=b"mp3-data")) as synthesize,
            ):
                app = create_app()
                endpoint = next(route.endpoint for route in app.routes if getattr(route, "path", None) == "/api/speech")
                response = await endpoint("Hola")

        self.assertEqual(response.media_type, "audio/mpeg")
        self.assertEqual(response.body, b"mp3-data")
        synthesize.assert_awaited_once_with("Hola", "es-ES-ElviraNeural")

    async def test_speech_endpoint_rejects_oversized_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = AppConfig(database_path=f"{directory}/speech.db")
            with (
                patch("sirvienta.app.get_config", return_value=config),
                patch("sirvienta.app.synthesize_speech", new=AsyncMock()) as synthesize,
            ):
                app = create_app()
                endpoint = next(route.endpoint for route in app.routes if getattr(route, "path", None) == "/api/speech")
                with self.assertRaises(HTTPException) as error:
                    await endpoint("a" * 1201)

        self.assertEqual(error.exception.status_code, 413)
        synthesize.assert_not_awaited()
