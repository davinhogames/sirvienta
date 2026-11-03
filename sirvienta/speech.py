from __future__ import annotations

from edge_tts import Communicate
from edge_tts.exceptions import NoAudioReceived


async def synthesize_speech(text: str, voice: str) -> bytes:
    communicate = Communicate(text, voice)
    audio = bytearray()
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            audio.extend(chunk["data"])
    if not audio:
        raise NoAudioReceived("El proveedor no devolvió audio.")
    return bytes(audio)
