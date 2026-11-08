package com.sirvienta.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class BundledSpeechOutput {
    interface Listener {
        void onComplete();
        void onError(Throwable error);
    }

    private static final String TAG = "SirvientaBundledTTS";
    private static final BundledSpeechOutput INSTANCE = new BundledSpeechOutput();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private OfflineTts tts;
    private AudioTrack audioTrack;
    private Future<?> currentTask;
    private long playbackId;

    private BundledSpeechOutput() { }

    static BundledSpeechOutput getInstance() {
        return INSTANCE;
    }

    synchronized void prepare(Context context) throws IOException {
        getOrCreateTts(context.getApplicationContext());
    }

    synchronized void speak(Context context, String text, Listener listener) {
        stop();
        long requestId = ++playbackId;
        currentTask = worker.submit(() -> {
            try {
                OfflineTts synthesizer = getOrCreateTts(context.getApplicationContext());
                GeneratedAudio audio = synthesizer.generate(
                        text,
                        LocalVoiceAssets.TTS_SPEAKER_ID,
                        1.0f);
                playAudio(audio.getSamples(), audio.getSampleRate(), requestId);
                if (requestId == currentPlaybackId()) {
                    listener.onComplete();
                }
            } catch (RuntimeException | IOException error) {
                Log.e(TAG, "No se pudo sintetizar o reproducir la voz incluida.", error);
                if (requestId == currentPlaybackId()) {
                    listener.onError(error);
                }
            }
        });
    }

    synchronized void stop() {
        playbackId++;
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (IllegalStateException error) {
                Log.w(TAG, "La reproducción ya se había detenido.", error);
            }
            audioTrack.release();
            audioTrack = null;
        }
    }

    private synchronized long currentPlaybackId() {
        return playbackId;
    }

    private synchronized OfflineTts getOrCreateTts(Context context) throws IOException {
        if (tts != null) {
            return tts;
        }
        java.io.File espeakData = LocalVoiceAssets.ensureEspeakData(context);
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(LocalVoiceAssets.TTS_MODEL_ASSET);
        vits.setTokens(LocalVoiceAssets.TTS_TOKENS_ASSET);
        vits.setDataDir(espeakData.getAbsolutePath());
        vits.setNoiseScale(0.667f);
        vits.setNoiseScaleW(0.8f);
        vits.setLengthScale(1.0f);

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(2);
        model.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(2);
        config.setSilenceScale(0.2f);
        tts = new OfflineTts(context.getAssets(), config);
        return tts;
    }

    private void playAudio(float[] samples, int sampleRate, long requestId) throws IOException {
        if (samples.length == 0 || sampleRate <= 0) {
            throw new IOException("El motor de voz no generó audio reproducible.");
        }
        int minimumBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBufferSize < 0) {
            throw new IOException("Android no admite el formato de audio generado por la voz.");
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minimumBufferSize, 8_192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        synchronized (this) {
            if (requestId != playbackId) {
                track.release();
                return;
            }
            audioTrack = track;
        }

        short[] buffer = new short[4_096];
        track.play();
        for (int offset = 0; offset < samples.length && requestId == currentPlaybackId();) {
            int count = Math.min(buffer.length, samples.length - offset);
            for (int index = 0; index < count; index++) {
                float sample = Math.max(-1.0f, Math.min(1.0f, samples[offset + index]));
                buffer[index] = (short) (sample * Short.MAX_VALUE);
            }
            int written = track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IOException("Android no pudo reproducir la voz generada.");
            }
            offset += written;
        }
        synchronized (this) {
            if (audioTrack == track) {
                track.stop();
                track.release();
                audioTrack = null;
            }
        }
    }
}
