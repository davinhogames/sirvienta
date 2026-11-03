from __future__ import annotations

import io
import json
import sys
import types
import unittest
import wave
from unittest.mock import patch

from sirvienta.voice import SpeechToText, VoiceConversation, VoiceInputError


class SpeechToTextTests(unittest.TestCase):
    def test_rejects_non_wav_input(self) -> None:
        with self.assertRaises(VoiceInputError):
            SpeechToText.__new__(SpeechToText).transcribe_wav(b"not audio")

    def test_rejects_audio_with_wrong_sample_rate(self) -> None:
        audio_buffer = io.BytesIO()
        with wave.open(audio_buffer, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(8_000)
            audio.writeframes(b"\x00\x00" * 800)

        with self.assertRaises(VoiceInputError):
            SpeechToText.__new__(SpeechToText).transcribe_wav(audio_buffer.getvalue())

    def test_transcribes_valid_audio_using_local_vosk(self) -> None:
        class Recognizer:
            def __init__(self, model: object, sample_rate: int):
                self.sample_rate = sample_rate
                self.finished = False

            def AcceptWaveform(self, audio: bytes) -> bool:
                self.finished = True
                return True

            def FinalResult(self) -> str:
                return json.dumps({"text": "abre la calculadora"})

            def Result(self) -> str:
                return json.dumps({"text": "abre la calculadora"})

            def PartialResult(self) -> str:
                return json.dumps({"partial": "abre la"})

        audio_buffer = io.BytesIO()
        with wave.open(audio_buffer, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16_000)
            audio.writeframes(b"\x00\x00" * 800)

        fake_vosk = types.ModuleType("vosk")
        fake_vosk.KaldiRecognizer = Recognizer
        with (
            patch.dict(sys.modules, {"vosk": fake_vosk}),
            patch("sirvienta.voice._load_vosk_model", return_value=object()),
        ):
            result = SpeechToText.__new__(SpeechToText).transcribe_wav(audio_buffer.getvalue())

        self.assertEqual(result, "abre la calculadora")

    def test_streaming_recognizer_returns_partial_speech_before_endpoint(self) -> None:
        class Recognizer:
            def AcceptWaveform(self, audio: bytes) -> bool:
                return False

            def PartialResult(self) -> str:
                return json.dumps({"partial": "abre la calculadora"})

        transcript = SpeechToText.accept_audio(Recognizer(), b"\x00\x00" * 100)

        self.assertEqual(transcript, ("", "abre la calculadora"))

    def test_streaming_recognizer_returns_final_transcript(self) -> None:
        class Recognizer:
            def AcceptWaveform(self, audio: bytes) -> bool:
                return True

            def Result(self) -> str:
                return json.dumps({"text": "abre la calculadora"})

        transcript = SpeechToText.accept_audio(Recognizer(), b"\x00\x00" * 100)

        self.assertEqual(transcript, ("abre la calculadora", ""))


class VoiceConversationTests(unittest.TestCase):
    def test_ignores_speech_until_wake_word_is_heard(self) -> None:
        session = VoiceConversation()

        turn = session.process("abre la calculadora")

        self.assertFalse(session.active)
        self.assertFalse(turn.activated)
        self.assertFalse(turn.command)

    def test_wake_word_activates_conversation_and_extracts_attached_command(self) -> None:
        session = VoiceConversation()

        turn = session.process("Sirvienta, abre la calculadora")

        self.assertTrue(session.active)
        self.assertTrue(turn.activated)
        self.assertEqual(turn.command, "abre la calculadora")

    def test_wake_word_alone_activates_conversation(self) -> None:
        session = VoiceConversation()

        turn = session.process("Sirvienta")

        self.assertTrue(turn.activated)
        self.assertFalse(turn.command)
        self.assertTrue(session.active)

    def test_goodbye_closes_conversation(self) -> None:
        session = VoiceConversation()
        session.process("Sirvienta")

        turn = session.process("Adiós")

        self.assertTrue(turn.closed)
        self.assertFalse(turn.command)
        self.assertFalse(session.active)

    def test_goodbye_with_wake_word_closes_conversation(self) -> None:
        session = VoiceConversation()
        session.process("Sirvienta")

        turn = session.process("Adiós, Sirvienta")

        self.assertTrue(turn.closed)
        self.assertFalse(turn.command)
        self.assertFalse(session.active)

    def test_command_before_goodbye_is_returned_and_conversation_closes(self) -> None:
        session = VoiceConversation()
        session.process("Sirvienta")

        turn = session.process("abre Chrome, adiós Sirvienta")

        self.assertEqual(turn.command, "abre Chrome")
        self.assertTrue(turn.closed)
        self.assertFalse(session.active)

    def test_filler_words_are_ignored_after_wake_word(self) -> None:
        session = VoiceConversation()

        turn = session.process("Hola Sirvienta, le hablo, abre Chrome")

        self.assertTrue(turn.activated)
        self.assertEqual(turn.command, "abre Chrome")
        self.assertTrue(session.active)

    def test_wake_word_detection_works_with_leading_greetings(self) -> None:
        session = VoiceConversation()

        turn = session.process("oye sirvienta, dime la hora")

        self.assertTrue(turn.activated)
        self.assertEqual(turn.command, "dime la hora")
        self.assertTrue(session.active)


if __name__ == "__main__":
    unittest.main()
