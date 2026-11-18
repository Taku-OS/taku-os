package org.agentos.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.View;
import android.view.Window;
import java.io.File;

/** Compatibility voice host. Taku Agent owns system invocation on current images. */
public final class AssistantSession extends VoiceInteractionSession {
    static final File ROOT = NativeAssistantSurface.ROOT;
    static final File TASKS = NativeAssistantSurface.TASKS;
    private final NativeAssistantSurface surface;

    public AssistantSession(Context context) {
        super(context);
        surface = new NativeAssistantSurface(context, new NativeAssistantSurface.Host() {
            public Window window() { return getWindow().getWindow(); }
            public void hide() { AssistantSession.this.hide(); }
            public void launch(Intent intent) { startAssistantActivity(intent); }
            public void setUiEnabled(boolean enabled) { AssistantSession.this.setUiEnabled(enabled); }
        });
    }
    static boolean prepareJevTestCapture() { return NativeAssistantSurface.prepareJevTestCapture(); }
    @Override public void onPrepareShow(Bundle args, int flags) { super.onPrepareShow(args, flags); surface.onPrepareShow(args, flags); }
    @Override public View onCreateContentView() { return surface.onCreateContentView(); }
    @Override public void onShow(Bundle args, int flags) { super.onShow(args, flags); surface.onShow(args, flags); }
    @Override public void onHandleAssist(AssistState state) { super.onHandleAssist(state); surface.onHandleAssist(); }
    @Override public void onHandleScreenshot(Bitmap bitmap) { super.onHandleScreenshot(bitmap); surface.onHandleScreenshot(bitmap); }
    @Override public void onBackPressed() { surface.onBackPressed(); }
    @Override public void onHide() { surface.onHide(); super.onHide(); }
    @Override public void onDestroy() { surface.onDestroy(); super.onDestroy(); }
}
