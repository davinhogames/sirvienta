package com.sirvienta.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LocalConversationHistory {
    private static final String PREFS = "sirvienta";
    private static final String PREF_LOCAL_HISTORY = "local_history";

    private LocalConversationHistory() { }

    static String buildPrompt(Context context, String currentMessage) throws JSONException {
        StringBuilder prompt = new StringBuilder()
                .append("<|im_start|>system\n")
                .append("Eres Sirvienta, una asistente local del teléfono. Responde en español claro y natural. ")
                .append("No tienes acceso a Internet. Las acciones compatibles del móvil se ejecutan localmente ")
                .append("antes de pedirte una respuesta; no afirmes haber hecho otras acciones. ")
                .append("Las notas guardadas son datos persistentes del usuario y puedes usarlas para responder ")
                .append("preguntas sobre cosas que te pidió apuntar.<|im_end|>\n");

        String notes = LocalAssistantData.notesForPrompt(context);
        if (!notes.isEmpty()) {
            prompt.append("<|im_start|>system\nNotas guardadas del usuario:\n")
                    .append(notes)
                    .append("<|im_end|>\n");
        }
        JSONArray history = read(context);
        int firstMessage = Math.max(0, history.length() - 8);
        for (int index = firstMessage; index < history.length(); index++) {
            JSONObject message = history.optJSONObject(index);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "");
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String content = message.optString("content", "");
            if (content.length() > 500) {
                content = content.substring(0, 500);
            }
            prompt.append("<|im_start|>")
                    .append(role)
                    .append('\n')
                    .append(content)
                    .append("<|im_end|>\n");
        }
        return prompt.append("<|im_start|>user\n")
                .append(currentMessage)
                .append("<|im_end|>\n<|im_start|>assistant\n")
                .toString();
    }

    static void append(Context context, String role, String content) throws JSONException {
        JSONArray history = read(context);
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        history.put(message);
        while (history.length() > 12) {
            history.remove(0);
        }
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LOCAL_HISTORY, history.toString())
                .commit();
        if (!saved) {
            throw new JSONException("No se pudo guardar el historial local.");
        }
    }

    private static JSONArray read(Context context) throws JSONException {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LOCAL_HISTORY, "[]");
        return new JSONArray(stored);
    }
}
