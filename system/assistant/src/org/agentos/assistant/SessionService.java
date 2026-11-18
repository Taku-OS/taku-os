package org.agentos.assistant;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
public final class SessionService extends VoiceInteractionSessionService {
 @Override public VoiceInteractionSession onNewSession(Bundle b){return new AssistantSession(this);}
}
