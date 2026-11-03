package com.sirvienta.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LocalAssistantData {
    private static final String PREFS = "sirvienta";
    private static final String PREF_NOTES = "local_notes";
    private static final int MAX_NOTES = 100;

    private LocalAssistantData() { }

    static void addNote(Context context, String content) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray notes = new JSONArray(preferences.getString(PREF_NOTES, "[]"));
        JSONObject note = new JSONObject();
        note.put("content", content);
        note.put("created_at", System.currentTimeMillis());
        notes.put(note);
        while (notes.length() > MAX_NOTES) {
            notes.remove(0);
        }
        if (!preferences.edit().putString(PREF_NOTES, notes.toString()).commit()) {
            throw new JSONException("No se pudo guardar la nota en el teléfono.");
        }
    }

    static String listNotes(Context context) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray notes = new JSONArray(preferences.getString(PREF_NOTES, "[]"));
        if (notes.length() == 0) {
            return "Aún no tienes notas guardadas.";
        }
        StringBuilder result = new StringBuilder("Tus notas guardadas: ");
        int first = Math.max(0, notes.length() - 10);
        for (int index = first; index < notes.length(); index++) {
            JSONObject note = notes.optJSONObject(index);
            if (note == null) {
                continue;
            }
            if (result.length() > "Tus notas guardadas: ".length()) {
                result.append("; ");
            }
            result.append(note.optString("content"));
        }
        return result.toString();
    }

    static String notesForPrompt(Context context) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray notes = new JSONArray(preferences.getString(PREF_NOTES, "[]"));
        StringBuilder result = new StringBuilder();
        int first = Math.max(0, notes.length() - 20);
        for (int index = first; index < notes.length(); index++) {
            JSONObject note = notes.optJSONObject(index);
            if (note != null) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append("- ").append(note.optString("content"));
            }
        }
        return result.toString();
    }
}
