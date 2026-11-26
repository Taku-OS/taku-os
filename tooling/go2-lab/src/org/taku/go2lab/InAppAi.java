package org.taku.go2lab;

import android.app.Activity;
import android.view.View;
import android.view.inputmethod.*;
import org.agentos.ai.AiClient;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** System AI proposes plans; the local controller owns execution and revocation. */
final class InAppAi {
  final AiConsoleView view;
  private final Activity activity;
  private final AiClient client;
  private final Consumer<String> page;
  private final ExecutorService model=Executors.newSingleThreadExecutor(), controls=Executors.newCachedThreadPool();
  private final ScheduledExecutorService polling=Executors.newSingleThreadScheduledExecutor();
  private final AtomicInteger generation=new AtomicInteger();
  private volatile boolean foreground, destroyed, working, changing, enabled;
  private volatile JSONObject session, state;
  private String shownJob="", modelName="";
  private String lastRequest="";
  private final EncounterScript encounter=new EncounterScript();
  private String encounterNotice="";
  private volatile String scene="general";
  void setScene(String value){scene=value;if(!"encounter".equals(scene))view.ordinaryLabels();if(state!=null)render(state);else if(inEncounter())renderEncounter();}
  boolean hasEncounter(){return encounter.active();}
  private boolean inEncounter(){return hasEncounter()&&"encounter".equals(scene);}
  JSONObject encounterSnapshot(){
    try{return new JSONObject().put("phase",encounter.phase).put("request",encounter.request).put("manual",encounter.manual).put("pending",encounter.pending).put("pendingRequest",encounter.pendingRequest).put("pendingStage",encounter.pendingStage);}
    catch(JSONException impossible){throw new IllegalStateException(impossible);}
  }
  void leaveEncounter(){encounter.clear();encounterNotice="";scene="general";view.ordinaryLabels();if(state!=null)render(state);}
  void cancelSceneCommand(){encounter.cancelPending();}
  boolean sceneCommand(String text){
    String intent=EncounterScript.intent(text);
    if(!inEncounter()&&!"go".equals(intent))return false;
    if("audio_stop".equals(intent)){encounter.cancelPending();page.accept("scene:hold");renderEncounter();return true;}
    if(working||changing||state!=null&&(active(state.optJSONObject("job"))||state.optJSONObject("follow")!=null&&state.optJSONObject("follow").optBoolean("active"))){
      view.result.setText(view.english(R.string.ai_scene_busy));return true;
    }
    encounterNotice="";
    int ticket=encounter.queue(text);
    if(ticket==0){
      if(encounter.processing())return true;
      encounterNotice=view.english(R.string.ai_scene_examples);renderEncounter();return true;
    }
    scene="encounter";view.input.setText("");view.input.clearFocus();
    ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.input.getWindowToken(),0);
    page.accept("stay".equals(intent)||"return".equals(intent)||"complete".equals(intent)?"scene:hold":"scene:update");renderEncounter();
    if(!encounter.pendingStage.isEmpty())view.root.postDelayed(()->{
      if(!destroyed&&foreground&&encounter.advancePlanning(ticket)){page.accept("scene:update");renderEncounter();}
    },2800);
    view.root.postDelayed(()->{
      if(destroyed||!foreground)return;
      String action=encounter.settle(ticket);if(action.isEmpty())return;
      page.accept(action.equals("bark")?"scene:bark":action.equals("say")?"scene:say":"scene:update");renderEncounter();
    },encounter.delayMillis());
    return true;
  }
  private void renderEncounter(){
    view.sceneOwner(encounter.manual,encounter.phase);
    String phase=encounter.manual?"Manual":encounter.phase.equals("confirm")?"Ready":encounter.phase.equals("complete")?"Complete":encounter.phase.equals("holding")||encounter.phase.equals("return_hold")?"Holding":"Running";
    view.status.setText(phase+" · "+view.english(R.string.ai_scene_guidance));
    view.result.setText(encounterNotice.isEmpty()?view.english(encounter.phase.equals("confirm")?R.string.ai_scene_plan:R.string.ai_scene_examples):encounterNotice);
    if(encounter.processing()){
      boolean returning=encounter.pending.equals("return"),planning=encounter.pendingStage.equals("planning");
      int label=encounter.pendingStage.isEmpty()?R.string.ai_scene_processing:planning?(returning?R.string.ai_scene_plan_return:R.string.ai_scene_plan_target):(returning?R.string.ai_scene_locate_return:R.string.ai_scene_locate_target);
      view.status.setText(view.english(label));view.result.setText(encounter.pendingRequest);
      view.send.setEnabled(false);view.confirm.setEnabled(false);
      if(encounter.pending.equals("go"))view.confirm.setVisibility(View.GONE);
    }
  }
  private volatile String localError="";

  InAppAi(Activity activity, Consumer<String> page) {
    this.activity=activity; this.page=page; client=new AiClient(activity); view=new AiConsoleView(activity);
    view.send.setOnClickListener(v->submit());
    view.input.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEND){submit();return true;}return false;});
    view.settings.setOnClickListener(v->activity.startActivity(new android.content.Intent("org.agentos.action.AI_SETTINGS").setPackage("org.agentos.assistant")));
    view.mode.setOnClickListener(v->changeMode());
    view.cancel.setOnClickListener(v->stop());
    view.confirm.setOnClickListener(v->confirm());
    polling.scheduleWithFixedDelay(this::poll,0,800,TimeUnit.MILLISECONDS);
  }
  private void ui(Runnable work) { activity.runOnUiThread(()->{if(!destroyed)work.run();}); }
  private boolean current(int id) { return !destroyed && foreground && id==generation.get(); }
  private static boolean active(JSONObject job) {
    return job!=null && Arrays.asList("planning","confirming","running").contains(job.optString("status"));
  }
  private void poll() {
    if(!foreground || destroyed) return;
    int id=generation.get();
    try {
      JSONObject lease=session;
      if(lease!=null) { try{AppApi.post("assistant/heartbeat",lease);}catch(Exception ignored){} }
      JSONObject next=AppApi.get("assistant/state");
      if(!current(id) || changing) return;
      state=next;
      JSONObject job=next.optJSONObject("job");
      if(lease!=null && job!=null && lease.optString("id").equals(job.optString("id")) && !active(job)) session=null;
      ui(()->{if(current(id) && !changing)render(next);});
    } catch(Exception e) {
      ui(()->{if(current(id) && !changing){if(inEncounter())renderEncounter();else{view.status.setText(R.string.ai_service_offline);view.send.setEnabled(false);}}});
    }
  }
  void resume() {
    foreground=true;
    controls.execute(()->{
      try {
        JSONObject config=client.settings(); enabled=config.optBoolean("enabled");
        // The current system API resolves the app's routing group. `read` no longer exposes textModel.
        // Let the broker validate the binding and capabilities; never silently choose a different model.
        JSONObject resolved=enabled?client.resolve("text"):null;
        boolean available=resolved!=null && !resolved.optString("id").isEmpty()
          && resolved.optBoolean("textInput") && !"decision".equals(resolved.optString("kind"));
        localError=enabled&&!available?view.context.getString(R.string.ai_model_missing,""):"";
        enabled=enabled && available;
        String name=available?resolved.optString("name",resolved.optString("id")):""; ui(()->modelName=name);
      }
      catch(Exception e) { enabled=false; localError=String.valueOf(e.getMessage()); }
    });
  }
  void pause() {
    if(encounter.processing()){encounter.cancelPending();page.accept("scene:update");renderEncounter();}
    foreground=false; generation.incrementAndGet(); session=null;
    controls.execute(()->{try{AppApi.post("assistant/cancel",new JSONObject());}catch(Exception ignored){}});
  }
  void destroy() {
    encounter.cancelPending();
    destroyed=true; foreground=false; generation.incrementAndGet(); session=null;
    polling.shutdownNow(); model.shutdownNow(); controls.shutdown();
  }
  private void submit() {
    String request=view.input.getText().toString().trim();
    if(!request.isEmpty()&&foreground&&sceneCommand(request))return;
    if(request.isEmpty() || working || changing || !foreground || state==null || !"ai".equals(state.optString("owner")) || active(state.optJSONObject("job"))) return;
    if(!enabled){view.result.setText(localError.isEmpty()?view.context.getString(R.string.ai_authorize):localError);return;}
    int id=generation.incrementAndGet(); working=true; localError=""; shownJob="";
    lastRequest=request;view.input.setText("");
    view.result.setText(R.string.ai_planning); view.send.setEnabled(false); view.input.clearFocus();
    ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.input.getWindowToken(),0);
    model.execute(()->{
      try {
        if(!current(id))return;
        AppApi.post("assistant/cancel",new JSONObject());
        if(!current(id))return;
        boolean follow=request.matches("(?is).*(跟踪|追踪|跟随|跟着|跟我|follow|track).*");
        if(follow)AppApi.post("assistant/observe",new JSONObject());
        JSONObject started=AppApi.post("assistant/start",new JSONObject());
        JSONObject lease=new JSONObject().put("id",started.getString("id")).put("token",started.getString("token"));
        if(!current(id))return; session=lease;
        JSONObject robot=AppApi.get("state");
        JSONObject policy=AppApi.get("assistant/state");robot.put("observationOnly",policy.optBoolean("observationOnly"));robot.put("appScene",scene);
        try{robot.put("navigation",AppApi.get("navigator/state"));}catch(Exception ignored){}
        if("audio".equals(scene)||!policy.optBoolean("observationOnly"))try{robot.put("audio",AppApi.get("audio"));}catch(Exception ignored){}
        if(!current(id))return;
        JSONObject reply;
        try { reply=client.text(AiPlanner.prompt(request,robot)); }
        catch(Exception modelError) {
          String action=AiPlanner.fallbackFollowAction(request);
          if(action==null)throw modelError;
          JSONObject fallback=new JSONObject().put("actions",new JSONArray().put(action))
            .put("message",view.context.getString(R.string.ai_local_follow_message));
          reply=new JSONObject().put("text",fallback.toString()).put("model",view.context.getString(R.string.ai_local_follow));
        }
        if(!current(id))return;
        JSONObject plan=AiPlanner.parse(reply.optString("text",null));
        JSONObject payload=new JSONObject(lease.toString()).put("plan",plan);
        JSONObject next=AppApi.post("assistant/plan",payload);
        if(!current(id))return;
        String responseModel=reply.optString("model","");
        ui(()->{if(current(id)){modelName=responseModel;state=next;render(next);}});
      } catch(Exception e) {
        if(current(id)) {
          localError=String.valueOf(e.getMessage());
          try{AppApi.post("assistant/cancel",new JSONObject());}catch(Exception ignored){}
          session=null;
          ui(()->{if(current(id))view.result.setText(AiPresentation.error(view.context,localError));});
        }
      } finally { working=false; }
    });
  }
  private void confirm() {
    if(inEncounter()){sceneCommand("Confirm");return;}
    JSONObject lease=session;
    if(lease==null || changing) return;
    int id=generation.get(); view.confirm.setEnabled(false);
    controls.execute(()->{
      try { JSONObject next=AppApi.post("assistant/confirm",lease); if(current(id)){state=next;ui(()->{if(current(id))render(next);});} }
      catch(Exception e){if(current(id))localError=String.valueOf(e.getMessage());}
    });
  }
  private void changeMode() {
    if(inEncounter()){encounter.cancelPending();encounter.manual=!encounter.manual;page.accept("scene:hold");renderEncounter();return;}
    if(changing)return;
    String target=state!=null && "manual".equals(state.optString("owner")) ? "ai":"manual";
    int id=generation.incrementAndGet(); session=null; changing=true; localError="";
    view.confirm.setVisibility(View.GONE); view.send.setEnabled(false); view.mode.setEnabled(false);
    view.status.setText(R.string.ai_switching);
    controls.execute(()->{
      try {
        JSONObject next=AppApi.post("assistant/mode",new JSONObject().put("mode",target));
        if(current(id))ui(()->{if(current(id)){state=next;render(next); if(target.equals("manual") && next.isNull("error"))page.accept(next.optBoolean("observationOnly")?"follow_stop":"motion");}});
      }catch(Exception e){if(current(id))localError=String.valueOf(e.getMessage());}
      finally { changing=false; ui(()->{view.mode.setEnabled(true);if(current(id) && !localError.isEmpty())view.result.setText(AiPresentation.error(view.context,localError));}); }
    });
  }
  private void stop() {
    if(inEncounter()){sceneCommand("Done");return;}
    if("audio".equals(scene)) {
      page.accept("audio_preview_stop");
      int id=generation.incrementAndGet();session=null;localError="";
      view.confirm.setVisibility(View.GONE);view.result.setText(R.string.ai_stopping);
      controls.execute(()->{
        try{
          AppApi.post("assistant/cancel",new JSONObject());
          JSONObject audio=AppApi.post("audio/stop",new JSONObject());
          if(!audio.isNull("error"))throw new IllegalStateException(audio.optString("error"));
          if(current(id))ui(()->{if(current(id))view.result.setText(R.string.ai_sound_stopped);});
        }catch(Exception e){if(current(id)){localError=String.valueOf(e.getMessage());ui(()->{if(current(id))view.result.setText(AiPresentation.error(view.context,localError));});}}
      });
      return;
    }
    if(state!=null && (state.optBoolean("observationOnly") || (state.optJSONObject("follow")!=null && state.optJSONObject("follow").optBoolean("active"))))page.accept("follow_stop");
    int id=generation.incrementAndGet(); session=null; localError="";
    view.confirm.setVisibility(View.GONE); view.result.setText(R.string.ai_stopping);
    controls.execute(()->{
      try{JSONObject next=AppApi.post("assistant/stop",new JSONObject());if(current(id)){state=next;ui(()->{if(current(id)){render(next);view.result.setText(next.isNull("error")?view.context.getString(next.optBoolean("observationOnly")?R.string.ai_follow_stopped:R.string.ai_stop_receipt):AiPresentation.error(view.context,next.optString("error")));}});}}
      catch(Exception e){if(current(id)){localError=String.valueOf(e.getMessage());ui(()->{if(current(id))view.result.setText(AiPresentation.error(view.context,localError));});}}
    });
  }
  private void render(JSONObject next) {
    if(inEncounter()){renderEncounter();return;}
    boolean ai="ai".equals(next.optString("owner")); JSONObject job=next.optJSONObject("job");
    view.owner(ai,working || changing || active(job) || !enabled);
    view.mode.setEnabled(!changing);
    String robot=next.isNull("robotMode")?view.context.getString(R.string.ai_unknown):next.optString("robotMode");
    String status=view.context.getString(ai?R.string.ai_owner_ai:R.string.ai_owner_manual,robot);
    boolean observation=next.optBoolean("observationOnly");
    if(observation)status=view.context.getString(!ai?R.string.ai_follow_stopped:"follow".equals(scene)?R.string.ai_visual_status:R.string.ai_ready);
    JSONObject followState=next.optJSONObject("follow");
    if(followState!=null && followState.optBoolean("active"))status=view.context.getString(R.string.ai_auto_follow_status);
    boolean audio="audio".equals(scene);
    if(audio)status=view.context.getString(R.string.ai_sound_status);
    view.cancel.setText(observation&&"follow".equals(scene)?R.string.ai_follow_stop:R.string.ai_stop);
    view.cancel.setContentDescription(view.context.getString(observation&&"follow".equals(scene)?R.string.ai_follow_stop:R.string.ai_stop_label));
    if(audio){view.cancel.setText(R.string.ai_sound_stop);view.cancel.setContentDescription(view.context.getString(R.string.ai_sound_stop));}
    if(ai && !enabled) status=view.context.getString(localError.isEmpty()?R.string.ai_authorize:R.string.ai_check_settings);
    if(!modelName.isEmpty()&&!modelName.startsWith("route-")&&!modelName.startsWith("group/"))status+=" · "+modelName;
    if(working||active(job))status=view.context.getString(R.string.ai_working);
    view.status.setText(status);
    boolean confirming=ai && session!=null && job!=null && "confirming".equals(job.optString("status"));
    view.result.setMaxLines(confirming?5:2);
    view.confirm.setVisibility(confirming?View.VISIBLE:View.GONE); view.confirm.setEnabled(confirming);
    if(!localError.isEmpty())view.result.setText(AiPresentation.error(view.context,localError));
    else if(!next.isNull("error"))view.result.setText(AiPresentation.error(view.context,next.optString("error")));
    else if(job!=null)view.result.setText(AiPresentation.result(view.context,job));
    else view.result.setText("");
    if(job!=null && "failed".equals(job.optString("status")) && !shownJob.equals(job.optString("id")) && java.util.Arrays.asList("ROBOT_LOCATION_REQUIRED","ROBOT_ANCHOR_REQUIRED","POSITION_TOO_UNCERTAIN","MAP_ROUTE_REQUIRED").contains(job.optString("error"))) {
      shownJob=job.optString("id");page.accept("navigator");
    }
    if(job!=null && "completed".equals(job.optString("status")) && !shownJob.equals(job.optString("id"))) {
      shownJob=job.optString("id"); JSONArray steps=job.optJSONArray("results");
      if(steps!=null)for(int i=0;i<steps.length();i++){
        JSONObject step=steps.optJSONObject(i); String action=step==null?"":step.optString("action");
        JSONObject object=step==null?null:step.optJSONObject("action");
        if(object!=null&&object.optString("action").startsWith("map_"))page.accept("amap");
        if(action.equals("navigation_preview")||action.equals("navigation_status"))page.accept("navigator");
        if(action.equals("follow_start"))page.accept("follow_auto:"+lastRequest);
        else if(action.equals("show_follow"))page.accept("follow:"+lastRequest);
        else if(action.equals("follow_stop"))page.accept("follow_stop");
        else if(action.startsWith("show_"))page.accept(action.substring(5));
      }
    }
  }
}
