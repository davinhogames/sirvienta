package com.sirvienta.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OfflineVoiceInput {
    interface Listener {
        void onWakeWord();
        void onCommand(String text);
        void onGoodbye();
        void onError(String message, Throwable error);
    }

    private static final String TAG = "SirvientaOfflineSTT";
    private static final Pattern WAKE_WORD = Pattern.compile("(?<![\\p{L}])sirvienta(?![\\p{L}])");

    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private Listener listener;
    private volatile boolean active;
    private volatile boolean awaitingCommand;
    private volatile boolean assistantEngaged;
    private String lastHandledHypothesis = "";
    private long lastHandledHypothesisAt;

    void start(Context context, Listener listener, boolean beginWithCommand) throws IOException {
        if (active) {
            return;
        }
        this.listener = listener;
        model = new Model(LocalVoiceAssets.ensureVoskModel(context).getAbsolutePath());
        try {
            recognizer = new Recognizer(model, 16_000.0f);
            speechService = new SpeechService(recognizer, 16_000.0f);
            active = true;
            awaitingCommand = beginWithCommand;
            assistantEngaged = beginWithCommand;
            boolean started = speechService.startListening(new org.vosk.android.RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) { }

                @Override
                public void onResult(String hypothesis) {
                    handleHypothesis(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    handleHypothesis(hypothesis);
                }

                @Override
                public void onError(Exception error) {
                    active = false;
                    Listener current = OfflineVoiceInput.this.listener;
                    if (current != null) {
                        current.onError("Falló el reconocimiento de voz local.", error);
                    }
                }

                @Override
                public void onTimeout() { }
            });
            if (!started) {
                active = false;
                throw new IOException("El micrófono ya está ocupado por otro reconocimiento.");
            }
        } catch (IOException | RuntimeException error) {
            active = false;
            close();
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("No se pudo iniciar el reconocimiento de voz offline.", error);
        }
    }

    void beginCommand() {
        if (!active || speechService == null) {
            return;
        }
        assistantEngaged = true;
        awaitingCommand = true;
        speechService.reset();
    }

    void pauseForResponse() {
        awaitingCommand = false;
        if (speechService != null) {
            speechService.setPause(true);
        }
    }

    void resumeAfterResponse() {
        if (!active || speechService == null) {
            return;
        }
        speechService.reset();
        speechService.setPause(false);
    }

    boolean isActive() {
        return active;
    }

    void close() {
        active = false;
        awaitingCommand = false;
        assistantEngaged = false;
        if (speechService != null) {
            speechService.cancel();
            speechService.shutdown();
            speechService = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        listener = null;
    }

    private void handleHypothesis(String json) {
        if (!active || json == null || json.isEmpty()) {
            return;
        }
        String text;
        try {
            text = new JSONObject(json).optString("text", "").trim();
        } catch (JSONException error) {
            Log.e(TAG, "Vosk devolvió un resultado no válido.", error);
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        String normalizedHypothesis = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
        long now = System.currentTimeMillis();
        if (normalizedHypothesis.equals(lastHandledHypothesis)
                && now - lastHandledHypothesisAt < 1_200L) {
            return;
        }
        lastHandledHypothesis = normalizedHypothesis;
        lastHandledHypothesisAt = now;

        Listener current = listener;
        if (current == null) {
            return;
        }
        if (isGoodbye(text)) {
            if (assistantEngaged) {
                assistantEngaged = false;
                pauseForResponse();
                current.onGoodbye();
            }
            return;
        }
        if (awaitingCommand) {
            awaitingCommand = false;
            Matcher wakeWord = WAKE_WORD.matcher(text.toLowerCase(Locale.ROOT));
            if (wakeWord.find()) {
                String command = text.substring(wakeWord.end())
                        .trim()
                        .replaceAll("^[,.:;\\s]+", "");
                if (command.isEmpty()) {
                    assistantEngaged = !assistantEngaged;
                    awaitingCommand = assistantEngaged;
                    current.onWakeWord();
                    return;
                }
                text = command;
            }
            assistantEngaged = true;
            pauseForResponse();
            current.onCommand(text);
            return;
        }

        Matcher wakeWord = WAKE_WORD.matcher(text.toLowerCase(Locale.ROOT));
        if (!wakeWord.find()) {
            if (!assistantEngaged) {
                return;
            }
            pauseForResponse();
            current.onCommand(text);
            return;
        }
        String command = text.substring(wakeWord.end()).trim().replaceAll("^[,.:;\\s]+", "");
        if (command.isEmpty()) {
            assistantEngaged = !assistantEngaged;
            awaitingCommand = assistantEngaged;
            current.onWakeWord();
        } else {
            assistantEngaged = true;
            pauseForResponse();
            current.onCommand(command);
        }
    }

    private boolean isGoodbye(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[,.!?;:]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.matches(
                "^(?:ad[ií]os|hasta luego|hasta pronto|me voy)(?:\\s+sirvienta)?$");
    }
}
