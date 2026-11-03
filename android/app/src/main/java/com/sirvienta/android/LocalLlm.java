package com.sirvienta.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;

final class LocalLlm {
    private static final int MAX_PREDICT_TOKENS = 384;
    private static boolean nativeLibraryLoaded;
    private static boolean modelLoaded;

    private LocalLlm() { }

    static synchronized void load(Context context) throws IOException {
        if (modelLoaded) {
            return;
        }
        if (!nativeLibraryLoaded) {
            try {
                System.loadLibrary("sirvienta_llm");
                nativeLibraryLoaded = true;
            } catch (UnsatisfiedLinkError error) {
                throw new IllegalStateException(
                        "No se pudo cargar el motor local de inteligencia artificial.", error);
            }
        }

        File modelFile = LocalModelFiles.materializeLlmModel(context);
        nativeLoadModel(modelFile.getAbsolutePath());
        modelLoaded = true;
    }

    static synchronized String generate(String prompt, int maxTokens) {
        if (!modelLoaded) {
            throw new IllegalStateException("El modelo local todavía no está cargado.");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío.");
        }
        if (maxTokens < 1 || maxTokens > MAX_PREDICT_TOKENS) {
            throw new IllegalArgumentException("El límite de respuesta local no es válido.");
        }

        String reply = nativeGenerate(prompt, maxTokens);
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalStateException("El modelo local no generó una respuesta.");
        }
        return reply.trim();
    }

    static synchronized void close() {
        if (modelLoaded) {
            nativeUnloadModel();
            modelLoaded = false;
        }
    }

    private static native void nativeLoadModel(String modelPath);
    private static native String nativeGenerate(String prompt, int maxTokens);
    private static native void nativeUnloadModel();
}
