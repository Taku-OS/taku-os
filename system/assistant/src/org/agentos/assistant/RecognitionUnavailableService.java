package org.agentos.assistant;
import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
/** Required voice-service metadata; no microphone or fabricated transcription. */
public final class RecognitionUnavailableService extends RecognitionService {
 @Override protected void onStartListening(Intent i, Callback cb){try{cb.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED);}catch(Exception ignored){}}
 @Override protected void onStopListening(Callback cb){}
 @Override protected void onCancel(Callback cb){}
}
