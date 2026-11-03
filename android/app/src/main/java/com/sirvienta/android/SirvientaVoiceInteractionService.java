package com.sirvienta.android;

import android.service.voice.VoiceInteractionService;
import android.util.Log;

public class SirvientaVoiceInteractionService extends VoiceInteractionService {
    private static final String TAG = "SirvientaVoiceService";

    @Override
    public void onReady() {
        super.onReady();
        Log.i(TAG, "Sirvienta está disponible como asistente de Android.");
    }
}
