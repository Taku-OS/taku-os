package org.agentos.assistant;

import android.service.voice.VoiceInteractionService;

public final class AssistantService extends VoiceInteractionService {
    public static volatile AssistantService active;

    @Override
    public void onReady() {
        super.onReady();
        active = this;
        BackgroundScreenTasks.restore(this);
    }

    @Override
    public void onShutdown() {
        if (active == this) active = null;
        super.onShutdown();
    }

    @Override
    public void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }
}
