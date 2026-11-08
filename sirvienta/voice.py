from __future__ import annotations

import io
import json
import re
import tempfile
import threading
import unicodedata
import urllib.error
import urllib.request
import wave
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:  # pragma: no cover - optional dependency
    import pyttsx3
except Exception:  # pragma: no cover
    pyttsx3 = None

try:  # pragma: no cover - optional dependency
    import speech_recognition as sr
except Exception:  # pragma: no cover
    sr = None


class VoiceError(Exception):
    status_code = 500


class VoiceInputError(VoiceError):
    status_code = 400


class VoiceModelError(VoiceError):
    status_code = 503


_VOSK_MODEL_NAME = "vosk-model-small-es-0.42"
_VOSK_MODEL_URL = f"https://alphacephei.com/vosk/models/{_VOSK_MODEL_NAME}.zip"
_VOSK_MODEL_PATH = Path(__file__).resolve().parent.parent / "data" / _VOSK_MODEL_NAME
_vosk_model: Any = None
_vosk_model_lock = threading.Lock()


def _load_vosk_model() -> Any:
    global _vosk_model

    if _vosk_model is not None:
        return _vosk_model
    with _vosk_model_lock:
        if _vosk_model is not None:
            return _vosk_model
        try:
            from vosk import Model
        except ImportError as error:
            raise VoiceModelError("Falta el motor local de voz. Instala las dependencias de SIRVIENTA.") from error

        model_path = _VOSK_MODEL_PATH
        if not (model_path / "am" / "final.mdl").is_file():
            try:
                model_path.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.TemporaryDirectory(dir=model_path.parent) as temporary_directory:
                    temporary_path = Path(temporary_directory)
                    archive_path = temporary_path / "model.zip"
                    with urllib.request.urlopen(_VOSK_MODEL_URL, timeout=60) as response:
                        with archive_path.open("wb") as archive_file:
                            total_downloaded = 0
                            while chunk := response.read(1024 * 1024):
                                total_downloaded += len(chunk)
                                if total_downloaded > 100 * 1024 * 1024:
                                    raise VoiceModelError("El paquete del modelo de voz supera el tamaño permitido.")
                                archive_file.write(chunk)

                    extracted_path = temporary_path / "extracted"
                    extracted_path.mkdir()
                    with zipfile.ZipFile(archive_path) as archive:
                        if sum(item.file_size for item in archive.infolist()) > 200 * 1024 * 1024:
                            raise VoiceModelError("El paquete del modelo de voz supera el tamaño permitido.")
                        for item in archive.infolist():
                            relative_path = Path(item.filename)
                            if relative_path.is_absolute() or ".." in relative_path.parts:
                                raise VoiceModelError("El paquete descargado contiene rutas no válidas.")
                            destination = extracted_path / relative_path
                            if item.is_dir():
                                destination.mkdir(parents=True, exist_ok=True)
                            else:
                                destination.parent.mkdir(parents=True, exist_ok=True)
                                with archive.open(item) as source, destination.open("wb") as target:
                                    while chunk := source.read(1024 * 1024):
                                        target.write(chunk)

                    downloaded_model = extracted_path / _VOSK_MODEL_NAME
                    if not (downloaded_model / "am" / "final.mdl").is_file():
                        raise VoiceModelError("El modelo descargado no contiene un modelo español válido.")
                    downloaded_model.rename(model_path)
            except VoiceError:
                raise
            except (OSError, urllib.error.URLError, zipfile.BadZipFile) as error:
                raise VoiceModelError(
                    f"No se pudo descargar el modelo de voz en español: {error}"
                ) from error

        try:
            _vosk_model = Model(str(model_path))
        except (OSError, RuntimeError) as error:
            raise VoiceModelError(f"No se pudo cargar el modelo local de voz: {error}") from error
        return _vosk_model


@dataclass
class VoiceConfig:
    wake_word: str = "Sirvienta"
    language: str = "es-ES"


class WakeWordDetector:
    def __init__(self, config: VoiceConfig):
        self.config = config

    def detect(self, text: str) -> bool:
        normalized = text.strip().lower()
        token = self.config.wake_word.lower()
        return normalized == token or normalized.startswith(token + " ") or normalized.endswith(" " + token) or token in normalized


class SpeechToText:
    def __init__(self, config: VoiceConfig):
        self.config = config

    def transcribe(self, source: str | None = None) -> str:
        if source == "keyboard":
            return input("Escribe el comando: ")
        if sr is None:
            return ""
        recognizer = sr.Recognizer()
        with sr.Microphone() as mic:
            recognizer.adjust_for_ambient_noise(mic)
            audio = recognizer.listen(mic)
        try:
            return recognizer.recognize_google(audio, language=self.config.language)
        except Exception:
            return ""

    def transcribe_wav(self, audio_data: bytes) -> str:
        if not audio_data or len(audio_data) > 512_000:
            raise VoiceInputError("La grabación está vacía o supera el tamaño permitido.")
        try:
            with wave.open(io.BytesIO(audio_data), "rb") as audio:
                if (
                    audio.getnchannels() != 1
                    or audio.getsampwidth() != 2
                    or audio.getframerate() != 16_000
                    or audio.getcomptype() != "NONE"
                ):
                    raise VoiceInputError("El audio debe ser WAV mono PCM de 16 bits y 16 kHz.")
                if audio.getnframes() > 16_000 * 15:
                    raise VoiceInputError("La grabación no puede superar los 15 segundos.")
                pcm_data = audio.readframes(audio.getnframes())
        except (wave.Error, EOFError) as error:
            raise VoiceInputError("No se recibió un archivo WAV válido.") from error

        try:
            from vosk import KaldiRecognizer
        except ImportError as error:
            raise VoiceModelError("Falta el motor local de voz. Instala las dependencias de SIRVIENTA.") from error

        model = _load_vosk_model()
        recognizer = KaldiRecognizer(model, 16_000)
        for offset in range(0, len(pcm_data), 8_000):
            recognizer.AcceptWaveform(pcm_data[offset : offset + 8_000])
        result = json.loads(recognizer.FinalResult())
        return result.get("text", "").strip()

    @staticmethod
    def create_streaming_recognizer() -> Any:
        try:
            from vosk import KaldiRecognizer
        except ImportError as error:
            raise VoiceModelError("Falta el motor local de voz. Instala las dependencias de SIRVIENTA.") from error
        return KaldiRecognizer(_load_vosk_model(), 16_000)

    @staticmethod
    def accept_audio(recognizer: Any, pcm_data: bytes) -> tuple[str, str]:
        if not pcm_data or len(pcm_data) > 16_000:
            raise VoiceInputError("El fragmento de audio está vacío o supera el tamaño permitido.")
        if recognizer.AcceptWaveform(pcm_data):
            transcript = json.loads(recognizer.Result()).get("text", "").strip()
            return transcript, ""
        partial = json.loads(recognizer.PartialResult()).get("partial", "").strip()
        return "", partial


@dataclass
class VoiceTurn:
    activated: bool = False
    command: str = ""
    closed: bool = False


class VoiceConversation:
    _LEADING_FILLERS = (
        "hola",
        "oye",
        "escuchame",
        "escúchame",
        "te hablo",
        "le hablo",
        "buenas",
        "buenos dias",
        "buenos días",
        "buenas tardes",
        "buenas noches",
    )

    def __init__(self, wake_word: str = "Sirvienta"):
        self.wake_word = self._normalize(wake_word)
        self.active = False

    @staticmethod
    def _normalize(text: str) -> str:
        normalized = unicodedata.normalize("NFKD", text.casefold())
        return "".join(character for character in normalized if not unicodedata.combining(character))

    def _clean_command(self, transcript: str) -> str:
        if not transcript:
            return ""
        cleaned = transcript.strip(" ,.!?¿¡")
        if not cleaned:
            return ""

        while cleaned:
            matches = list(re.finditer(r"\w+", cleaned, re.UNICODE))
            if not matches:
                return ""
            stripped = False
            for filler in sorted(self._LEADING_FILLERS, key=len, reverse=True):
                filler_words = re.findall(r"\w+", filler, re.UNICODE)
                if len(matches) < len(filler_words):
                    continue
                candidate = " ".join(self._normalize(match.group()) for match in matches[: len(filler_words)])
                if candidate == self._normalize(filler):
                    cleaned = cleaned[matches[len(filler_words) - 1].end():].strip(" ,.!?¿¡")
                    stripped = True
                    break
            if not stripped:
                return cleaned.strip(" ,.!?¿¡")
        return ""

    def process(self, transcript: str) -> VoiceTurn:
        matches = list(re.finditer(r"\w+", transcript, re.UNICODE))
        words = [self._normalize(match.group()) for match in matches]
        if not words:
            return VoiceTurn()

        if not self.active:
            try:
                wake_index = words.index(self.wake_word)
            except ValueError:
                return VoiceTurn()
            self.active = True
            remaining = self._clean_command(transcript[matches[wake_index].end():])
            if not remaining:
                return VoiceTurn(activated=True)
            turn = self._active_turn(remaining)
            return VoiceTurn(activated=True, command=turn.command, closed=turn.closed)

        return self._active_turn(transcript)

    def _active_turn(self, transcript: str) -> VoiceTurn:
        cleaned = self._clean_command(transcript)
        if not cleaned:
            return VoiceTurn()
        matches = list(re.finditer(r"\w+", cleaned, re.UNICODE))
        words = [self._normalize(match.group()) for match in matches]
        if words == [self.wake_word]:
            self.active = False
            return VoiceTurn(closed=True)
        for index, word in enumerate(words):
            if word == "adios":
                command = cleaned[:matches[index].start()].strip(" ,.!?¿¡")
                self.active = False
                return VoiceTurn(command=command, closed=True)
        return VoiceTurn(command=cleaned)


class TextToSpeech:
    def __init__(self, config: VoiceConfig):
        self.config = config
        self.engine = None
        if pyttsx3 is not None:
            try:
                self.engine = pyttsx3.init()
                self.engine.setProperty("rate", 170)
                self.engine.setProperty("voice", self.config.language)
            except Exception:  # pragma: no cover
                self.engine = None

    def speak(self, text: str) -> None:
        if self.engine is not None:
            try:
                self.engine.say(text)
                self.engine.runAndWait()
                return
            except Exception:
                pass
        print(f"[TTS:{self.config.language}] {text}")
