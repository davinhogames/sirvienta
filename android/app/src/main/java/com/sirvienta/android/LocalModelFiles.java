package com.sirvienta.android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class LocalModelFiles {
    static final String MODEL_ASSET = "llm/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf";

    private LocalModelFiles() { }

    static File materializeLlmModel(Context context) throws IOException {
        File modelDirectory = new File(context.getFilesDir(), "models");
        if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) {
            throw new IOException("No se pudo crear el directorio local de modelos.");
        }

        String fileName = MODEL_ASSET.substring(MODEL_ASSET.lastIndexOf('/') + 1);
        File modelFile = new File(modelDirectory, fileName);
        File temporaryFile = new File(modelDirectory, fileName + ".tmp");

        try (AssetFileDescriptor asset = context.getAssets().openFd(MODEL_ASSET)) {
            long expectedLength = asset.getLength();
            if (expectedLength <= 0) {
                throw new IOException("El modelo local está vacío o no se puede leer.");
            }
            if (modelFile.isFile() && modelFile.length() == expectedLength) {
                return modelFile;
            }

            long copied = 0;
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream input = asset.createInputStream();
                    FileOutputStream output = new FileOutputStream(temporaryFile)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.getFD().sync();
            }

            if (copied != expectedLength) {
                temporaryFile.delete();
                throw new IOException(
                        "El modelo se copió incompleto (" + copied + " de " + expectedLength + " bytes).");
            }
        } catch (IOException error) {
            temporaryFile.delete();
            throw error;
        }

        if (modelFile.exists() && !modelFile.delete()) {
            temporaryFile.delete();
            throw new IOException("No se pudo reemplazar la copia anterior del modelo.");
        }
        if (!temporaryFile.renameTo(modelFile)) {
            temporaryFile.delete();
            throw new IOException("No se pudo guardar el modelo local en el almacenamiento de la app.");
        }
        return modelFile;
    }
}
