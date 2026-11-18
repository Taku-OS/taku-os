package org.agentos.assistant;

import android.Manifest;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.speech.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Supplier;

/** Compact mobile composer controls backed by actual per-thread runtime options. */
final class ComposerToolbar extends LinearLayout {
    interface Actions { void attach(String mime); void dictated(String text, int position); int insertion(); void message(String text); void more(View anchor); boolean continuousReady(); boolean continuousInput(String text); void interruptVoice(String taskId, java.util.function.Consumer<Boolean> result); }
    final AgentRepository repository;
    final DrawerTheme theme;
    final Supplier<String> thread;
    final Actions actions;
    final ImageButton tools;
    JSONArray catalog = new JSONArray();
    org.agentos.audio.AudioClient audio; String audioSession="", audioOpen=""; ComposerDraft voiceDraft; int voicePosition;
    boolean listening, detached, applyingVoice;long voiceGeneration;
    org.agentos.audio.AudioConversation continuous;
    /** Pre-engine on-device recognizer, used only while the Audio Engine is not installed (issue #26 #4). */
    SpeechRecognizer speech;
    String voiceNotice="";

    ComposerToolbar(Context context, DrawerTheme theme, AgentRepository repository, Supplier<String> thread, Actions actions) {
        super(context); this.theme=theme; this.repository=repository; this.thread=thread; this.actions=actions;
        setGravity(Gravity.CENTER_VERTICAL);
        tools=control(R.drawable.ic_taku_plus,s(R.string.chat_tools),this::toolsMenu);
        addView(tools);
        addView(new android.view.View(context),new LayoutParams(0,1,1));
        tools.setOnLongClickListener(v->{menu(tools,"Dictation language",AudioEngine.available(getContext())?new String[]{"Chinese dictation","English dictation","Continuous conversation · Take turns","Continuous conversation · Interruptible (experimental)"}:new String[]{"Chinese dictation","English dictation"},index->{if(index>=2){continuousVoice(index==3);return;}cancelVoice();ComposerPreferences.set(repository.preferences(),thread.get(),"dictationLanguage",index==0?"zh-CN":"en-US");actions.message(index==0?"Dictation language: Chinese":"Dictation language: English");});return true;});
    }
    String s(int id){ return getContext().getString(id); }
    int dp(int value){return theme.dp(getContext(),value);}
    ImageButton control(int resource,String label,Runnable action){ImageButton button=new ImageButton(getContext());button.setImageResource(resource);button.setColorFilter(theme.ink);button.setContentDescription(label);button.setTooltipText(label);button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);button.setPadding(dp(14),dp(14),dp(14),dp(14));button.setBackground(new RippleDrawable(ColorStateList.valueOf(theme.border),null,null));button.setLayoutParams(new LayoutParams(dp(48),dp(48)));button.setOnClickListener(v->action.run());return button;}
    String value(String key,String fallback){return ComposerPreferences.get(repository.preferences(),thread.get(),key,fallback);}
    void set(String key,String value){ComposerPreferences.set(repository.preferences(),thread.get(),key,value);actions.message(s(R.string.chat_updated));}
    void menu(View anchor,String title,String[] labels,java.util.function.IntConsumer callback){PopupMenu popup=new PopupMenu(getContext(),anchor.isShown()?anchor:getChildAt(0));for(int i=0;i<labels.length;i++)popup.getMenu().add(0,i,i,labels[i]);popup.setOnMenuItemClickListener(item->{callback.accept(item.getItemId());return true;});popup.show();}
    void attachments(){menu(getChildAt(0),s(R.string.chat_attach),new String[]{s(R.string.chat_camera),s(R.string.chat_image),s(R.string.chat_file)},index->actions.attach(index==0?"camera":index==1?"image/*":"*/*"));}
    void toolsMenu(){
        boolean engine=AudioEngine.available(getContext());
        List<String> labels=new ArrayList<>(Arrays.asList(s(R.string.chat_camera),s(R.string.chat_image),s(R.string.chat_file),s(listening?R.string.chat_voice_stop:R.string.chat_voice)));
        if(engine)labels.add(continuous!=null&&continuous.getActive()?"Leave continuous conversation":"Continuous conversation");
        labels.add(s(R.string.chat_more));
        final int more=labels.size()-1;
        menu(tools,s(R.string.chat_tools),labels.toArray(new String[0]),index->{
            if(index<3)actions.attach(index==0?"camera":index==1?"image/*":"*/*");
            else if(index==3)voice();
            else if(index==more)tools.post(()->{if(!detached&&isShown())actions.more(tools);});
            else {if(continuous!=null&&continuous.getActive())cancelVoice();else continuousVoice();}
        });
    }
    void withModels(Runnable ready){actions.message(s(R.string.chat_loading_models));NativeClient.rpc("model/list",AgentNativeView.object("limit",100),new NativeClient.Callback(){public void ready(JSONObject result){if(detached)return;catalog=result.optJSONArray("data");if(catalog==null||catalog.length()==0){catalog=new JSONArray();actions.message(s(R.string.chat_no_models));return;}ready.run();}public void failed(String message){actions.message(message);}});}
    JSONObject selected(){String id=value("model","");JSONObject defaultModel=null;for(int i=0;i<catalog.length();i++){JSONObject item=catalog.optJSONObject(i);if(item==null)continue;if(item.optString("model").equals(id))return item;if(item.optBoolean("isDefault"))defaultModel=item;}return defaultModel==null?catalog.optJSONObject(0):defaultModel;}
    void models(){withModels(()->{String[] labels=new String[catalog.length()+1];labels[0]=s(R.string.chat_default_model);for(int i=0;i<catalog.length();i++)labels[i+1]=catalog.optJSONObject(i).optString("displayName");menu(getChildAt(0),s(R.string.chat_model),labels,index->{if(index==0){JSONObject defaultModel=null;for(int i=0;i<catalog.length();i++)if(catalog.optJSONObject(i).optBoolean("isDefault"))defaultModel=catalog.optJSONObject(i);if(defaultModel!=null){set("model",defaultModel.optString("model"));set("effort",defaultModel.optString("defaultReasoningEffort","medium"));}}else{JSONObject chosen=catalog.optJSONObject(index-1);set("model",chosen.optString("model"));set("effort",chosen.optString("defaultReasoningEffort","medium"));}set("serviceTier","");});});}
    void efforts(){withModels(()->{JSONObject selected=selected();JSONArray supported=selected==null?null:selected.optJSONArray("supportedReasoningEfforts");if(supported==null||supported.length()==0){actions.message(s(R.string.chat_no_efforts));return;}String[] labels=new String[supported.length()];for(int i=0;i<labels.length;i++)labels[i]=AgentNativeViewEffort.label(getContext(),supported.optJSONObject(i).optString("reasoningEffort"));menu(getChildAt(0),s(R.string.chat_effort),labels,index->set("effort",supported.optJSONObject(index).optString("reasoningEffort")));});}
    void voice(){
        if(detached||!isShown())return;
        if(continuous!=null&&continuous.getActive()){cancelVoice();return;}
        if(!listening && NativeTurnView.isReading()){NativeTurnView.stopReading(this::voice);return;}
        if(listening){if(speech!=null)speech.stopListening();else if(audio!=null&&!audioSession.isEmpty())audio.finish(audioSession);else endVoice("Dictation stopped");return;}
        if(getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){actions.message("Allow microphone access in system app permissions first.");return;}
        if(!AudioEngine.available(getContext())){platformVoice();return;}
        final long generation=++voiceGeneration;
        voiceDraft=ComposerPreferences.draft(repository.preferences(),thread.get());voicePosition=Math.max(0,Math.min(actions.insertion(),voiceDraft.text.length()));
        listening=true;tools.setColorFilter(theme.accent);actions.message("Preparing local speech recognition…");
        audio=new org.agentos.audio.AudioClient(getContext(),new org.agentos.audio.AudioClient.Listener(){
            public void connected(){if(generation!=voiceGeneration||!listening||detached)return;try{audioOpen=audio.open(new JSONObject().put("purpose","DICTATION").put("language",ComposerPreferences.get(repository.preferences(),thread.get(),"dictationLanguage","zh-CN")));}catch(Exception e){endVoice("Audio engine request did not complete");}}
            public void event(String raw){if(generation!=voiceGeneration||detached||!listening)return;try{
                JSONObject e=new JSONObject(raw);
                if(e.optString("type").equals("response")){
                    if(e.optString("id").equals(audioOpen)){if(!e.optBoolean("ok")){endVoice(e.getJSONObject("error").optString("message"));return;}audioSession=e.getJSONObject("result").getString("sessionId");}
                    return;
                }
                JSONObject data=e.getJSONObject("data");
                if(!audioSession.isEmpty()&&!data.optString("sessionId").equals(audioSession))return;
                if(!voiceDraft.matches(ComposerPreferences.draft(repository.preferences(),voiceDraft.thread))||!voiceDraft.thread.equals(thread.get())){cancelVoice();return;}
                String kind=data.optString("kind");
                if(kind.equals("Running"))actions.message("Listening. Tap the microphone to finish");
                if(kind.equals("Text")){
                    String text=data.optString("text");
                    if(data.optBoolean("final")){
                        applyingVoice=true;try{String insert=(voicePosition>0?" ":"")+text;actions.dictated(insert,voicePosition);voicePosition+=insert.length();voiceDraft=ComposerPreferences.draft(repository.preferences(),voiceDraft.thread);}finally{applyingVoice=false;}
                    }else actions.message(text);
                }
                if(kind.equals("Closed")){String reason=data.optString("reason");endVoice(reason.equals("completed")?"Dictated text added to draft":reason.equals("canceled")?"Dictation stopped": "Dictation stopped ("+reason+"); the original draft was preserved");}
            }catch(Exception e){endVoice("Speech result did not complete. The original draft was preserved");}}
            public void failed(String message){if(generation==voiceGeneration)endVoice(message);}
        });
        try{audio.connect();}catch(Exception e){endVoice("Audio engine temporarily unavailable");}
    }
    /** On-device platform dictation (the pre-engine path), kept while the engine is not installed. */
    void platformVoice(){
        if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(getContext())){actions.message(s(R.string.chat_voice_unavailable));return;}
        final long generation=++voiceGeneration;
        final String voiceThread=thread.get();
        voiceDraft=ComposerPreferences.draft(repository.preferences(),voiceThread);voicePosition=Math.max(0,Math.min(actions.insertion(),voiceDraft.text.length()));
        speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(getContext());
        speech.setRecognitionListener(new RecognitionListener(){
            boolean current(){return !detached&&generation==voiceGeneration&&voiceThread.equals(thread.get());}
            public void onReadyForSpeech(Bundle b){if(current())actions.message(s(R.string.chat_listening));}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float rms){}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){if(current())tools.setColorFilter(theme.muted);}
            public void onError(int error){if(current())endVoice(s(R.string.chat_voice_failed));}
            public void onResults(Bundle results){
                if(!current())return;
                java.util.ArrayList<String> heard=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text=heard==null||heard.isEmpty()?"":heard.get(0).trim();
                if(!text.isEmpty()&&voiceDraft.matches(ComposerPreferences.draft(repository.preferences(),voiceThread))){
                    applyingVoice=true;try{actions.dictated((voicePosition>0?" ":"")+text,voicePosition);}finally{applyingVoice=false;}
                }
                endVoice(text.isEmpty()?s(R.string.chat_voice_failed):"Dictated text added to draft");
            }
            public void onPartialResults(Bundle b){}
            public void onEvent(int type,Bundle b){}
        });
        String language=value("dictationLanguage",getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        listening=true;tools.setColorFilter(theme.accent);
        speech.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false));
    }
    void continuousVoice(){continuousVoice(false);}
    void continuousVoice(boolean interruptible){
        cancelVoice();
        if(!AudioEngine.available(getContext())){actions.message("Continuous conversation requires a system audio engine, which this system does not provide");return;}
        if(interruptible&&!android.media.audiofx.AcousticEchoCanceler.isAvailable()){actions.message("This device has no echo cancellation. Use turn-taking mode");return;}
        if(detached||!isShown()||!actions.continuousReady()){actions.message("Finish the current task and clear the draft before starting continuous conversation");return;}
        if(getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){actions.message("Allow microphone permission first");return;}
        NativeTurnView.stopReading(()->{
            if(detached||!isShown()||!actions.continuousReady())return;
            final String boundThread=thread.get();
            continuous=new org.agentos.audio.AudioConversation(getContext(),new org.agentos.audio.AudioConversation.Listener(){
                public void state(boolean active,String message){
                    voiceNotice=message;tools.setColorFilter(active?theme.accent:theme.ink);tools.setStateDescription(message);
                    actions.message(message);
                }
                public boolean input(String text){if(!boundThread.equals(thread.get())||!actions.continuousReady())return false;applyingVoice=true;try{return actions.continuousInput(text);}finally{applyingVoice=false;}}
                public void preserve(String text){
                    if(text.isBlank()||!boundThread.equals(thread.get())||!ComposerPreferences.draft(repository.preferences(),boundThread).isEmpty())return;
                    applyingVoice=true;try{actions.dictated(text,0);}finally{applyingVoice=false;}
                }
                public void interrupt(String taskId,org.agentos.audio.AudioConversation.InterruptResult result){
                    if(detached||!boundThread.equals(thread.get())){result.done(false);return;}
                    actions.interruptVoice(taskId,result::done);
                }
            });
            continuous.start(value("dictationLanguage","zh-CN"),interruptible);
        });
    }
    void acceptedVoice(String task){if(continuous!=null)continuous.accepted(task);}
    void replyVoice(String task,String text,boolean complete,boolean success){if(continuous!=null)continuous.answer(task,text,complete,success);}
    void beforeSubmit(){if(continuous==null||!continuous.getActive())cancelVoice();}
    boolean preservesVoiceNotice(){return listening||continuous!=null&&continuous.getActive()||!voiceNotice.isEmpty();}
    void draftChanged(){if(!applyingVoice)voiceNotice="";if((listening||continuous!=null&&continuous.getActive())&&!applyingVoice)cancelVoice();}
    void cancelVoice(){if(continuous!=null){continuous.stop("Left continuous conversation");continuous=null;}endVoice("");}
    void endVoice(String message){voiceNotice=message;boolean hadVoice=listening;voiceGeneration++;listening=false;audioSession="";audioOpen="";if(audio!=null){audio.close();audio=null;}if(speech!=null){speech.cancel();speech.destroy();speech=null;}tools.setColorFilter(theme.ink);if(hadVoice||!message.isEmpty())actions.message(message);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();detached=false;}
    @Override protected void onDetachedFromWindow(){detached=true;cancelVoice();super.onDetachedFromWindow();}
    static final class AgentNativeViewEffort {static String label(Context context,String value){switch(value){case"low":return context.getString(R.string.chat_effort_low);case"medium":return context.getString(R.string.chat_effort_medium);case"high":return context.getString(R.string.chat_effort_high);case"xhigh":return context.getString(R.string.chat_effort_xhigh);case"max":return context.getString(R.string.chat_effort_max);case"ultra":return context.getString(R.string.chat_effort_ultra);default:return value;}}}
}
