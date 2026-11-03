package com.sirvienta.android;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class LocalVoiceAssets {
    static final String VOSK_MODEL_ASSET = "vosk-model-small-es-0.42";
    static final String TTS_MODEL_ASSET = "tts/es_ES-carlfm-x_low.onnx";
    static final String TTS_TOKENS_ASSET = "tts/tokens.txt";
    static final String TTS_DATA_ASSET = "tts/espeak-ng-data";

    private LocalVoiceAssets() { }

    static File ensureVoskModel(Context context) throws IOException {
        return copyAssetDirectory(context, VOSK_MODEL_ASSET, "models/vosk-es-0.42", "vosk-es-0.42");
    }

    static File ensureEspeakData(Context context) throws IOException {
        return copyAssetDirectory(context, TTS_DATA_ASSET, "models/espeak-ng-data", "espeak-ng-data");
    }

    private static File copyAssetDirectory(
            Context context, String assetPath, String relativeTarget, String markerName) throws IOException {
        File modelsDirectory = new File(context.getFilesDir(), "models");
        File target = new File(context.getFilesDir(), relativeTarget);
        if (new File(target, ".complete").isFile()) {
            return target;
        }
        if (!modelsDirectory.isDirectory() && !modelsDirectory.mkdirs()) {
            throw new IOException("No se pudo crear el directorio privado de modelos.");
        }

        File temporary = new File(modelsDirectory, markerName + ".tmp");
        deleteTree(temporary);
        if (!temporary.mkdirs()) {
            throw new IOException("No se pudo preparar la copia temporal del modelo " + markerName + ".");
        }
        try {
            copyAssets(context.getAssets(), assetPath, temporary);
            File completeMarker = new File(temporary, ".complete");
            try (FileOutputStream output = new FileOutputStream(completeMarker)) {
                output.write(markerName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException error) {
            deleteTree(temporary);
            throw error;
        }

        deleteTree(target);
        if (!temporary.renameTo(target)) {
            deleteTree(temporary);
            throw new IOException("No se pudo guardar el modelo " + markerName + " en el almacenamiento privado.");
        }
        return target;
    }

    private static void copyAssets(AssetManager assets, String assetPath, File destination) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = destination.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("No se pudo crear el directorio para el recurso " + assetPath + ".");
            }
            try (InputStream input = assets.open(assetPath);
                    FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            return;
        }

        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("No se pudo crear el directorio del recurso " + assetPath + ".");
        }
        for (String child : children) {
            copyAssets(assets, assetPath + "/" + child, new File(destination, child));
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (!target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (!target.delete()) {
            throw new IOException("No se pudo limpiar la copia incompleta del modelo.");
        }
    }
}
