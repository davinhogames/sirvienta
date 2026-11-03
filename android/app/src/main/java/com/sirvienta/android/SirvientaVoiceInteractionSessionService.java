package com.sirvienta.android;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class SirvientaVoiceInteractionSessionService extends VoiceInteractionSessionService {
    static final String EXTRA_START_LISTENING = "start_listening_from_assistant";

    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override
            public void onShow(Bundle arguments, int showFlags) {
                super.onShow(arguments, showFlags);
                Intent launch = new Intent(
                        SirvientaVoiceInteractionSessionService.this,
                        MainActivity.class);
                launch.putExtra(EXTRA_START_LISTENING, true);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startVoiceActivity(launch);
                finish();
            }
        };
    }
}
