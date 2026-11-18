package org.agentos.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.agentos.surfaces.SurfaceSpec;
import org.agentos.surfaces.client.SurfaceClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Screen-task owner, held by the bound system assistant; never starts an Activity. */
final class BackgroundScreenTasks implements SurfaceClient.Listener {
    private static BackgroundScreenTasks current;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    private final Map<String,Question> questions=new ConcurrentHashMap<>();
    private final SurfaceClient surfaces;
    private final File saved;
    private boolean connected;
    private static final String INSTRUCTIONS="You are Taku's background phone Agent. The user accepted a screen suggestion. Work in the independent execution display; keep the user's foreground app untouched except for an explicitly requested final draft prepared through the dedicated tool. The task has a 15-minute execution budget and the user can stop it. "
        +"Use authorized memory_search/memory_get and the supplied original image to reuse known facts. Never OCR or transcribe the screenshot, and never invent missing facts. "
        +"For ordinary visible verification challenges such as sliders or picture puzzles, use the phone gesture tools to complete the normal on-screen interaction yourself without first asking the user. Observe each fresh challenge and verify the actual result. For a reveal-while-dragging challenge, use touch DOWN/MOVE and observe_background while held. MOVE to the final observed target, observe again if needed, then UP at exactly the last MOVE coordinates. UP only releases; it is not a drag-to command. Retain the same gesture and act within its 15-second idle deadline. Never bypass verification APIs, forge verification tokens, modify apps, or alter verification responses. After at most two failed attempts on the same barrier, stop retrying and explain the actual blocker through the bottom surface. "
        +"Minimize interruptions. When a necessary parameter or approval is missing, call taku_phone.ask_user with one concise question and 1-3 meaningful options; wait for the actual choice before proceeding. "
        +"Do not put clarification questions into a final chat reply or ask the user to open Taku. The current ask_user surface supports choices, not free-text entry; never pretend arbitrary text was entered. "
        +"Before committing an action, show the actual details with taku_phone.confirm_action and wait for the real response to this pending call. For a reservation, show the verified restaurant and branch, complete date and time, party size, and allergy note as separate fields; use notice style for the allergy. Do not infer a missing date or claim that the restaurant can accommodate an allergy without evidence. "
        +"Only decision=confirm from this exact confirmation authorizes submission of this one free reservation with precisely those details. Rendering, dismissing, expiring, a prior prototype preview, modify, cancel, and silence are not approval. If any detail changes, show a new confirmation and wait again. On modify, clarify the changed details; on cancel, do not submit. A reservation confirmation never authorizes payment: any charge requires a separate explicit confirmation of the payee and exact amount/currency. Never ask for passwords or verification codes. "
        +"After submission, inspect the real receipt and distinguish a confirmed reservation, a request awaiting merchant confirmation, and a failure. A submitted request is not a confirmed booking. Report only the verified result; do not promise merchant acceptance or allergy accommodation. "
        +"Never send messages to WeChat or other contacts, press Send, or use Enter to send. If the user explicitly requested a reply draft, use taku_phone.prepare_foreground_draft(packageName,conversationTitle,text) only to fill the input of the current original conversation after the actual result is known. The user will send it manually. Preserve any existing draft, do not switch to another conversation, and do not include unverified success claims. All other work stays in the independent execution display. "
        +"Report progress in concise Chinese. The final reply is displayed in a small bottom system panel: use at most 120 Chinese characters of plain text, without Markdown tables or headings. Lead with the actual outcome or unresolved blocker, then the most useful verified result and next step. Do not claim a booking or an app action succeeded without actual evidence.";

    private static final class Job {
        final String id,title;
        final long createdAt;
        volatile String thread="",task="",state="starting",result="";
        long revision;
        boolean starting,polling,hidden;
        volatile boolean cancelRequested;
        JevScreenDecision capture;
        Job(String id,String title,long createdAt) { this.id=id;this.title=title;this.createdAt=createdAt; }
        String surfaceId() { return "screen-"+id; }
    }
    private static final class Question {
        final Job job;
        final String key;
        final BackgroundQuestion presentation;
        long revision;
        boolean answering,hidden;
        Question(Job job,String key,BackgroundQuestion presentation) {
            this.job=job;this.key=key;this.presentation=presentation;
        }
        String surfaceId() { return "ask-"+key; }
    }

    private BackgroundScreenTasks(Context context) {
        this.context=context.getApplicationContext();saved=new File(context.getFilesDir(),"background-screen-tasks.json");
        surfaces=new SurfaceClient(context,this);
    }
    private static BackgroundScreenTasks get(Context context) {
        if(current==null)current=new BackgroundScreenTasks(context);
        return current;
    }
    static boolean ownsTask(Context context,String id) {
        if(current!=null && current.jobs.values().stream().anyMatch(job->id.equals(job.task)))return true;
        try {
            File file=new File(context.getFilesDir(),"background-screen-tasks.json");
            if(!file.isFile() || file.length()>65536)return false;
            JSONArray rows=new JSONArray(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(int i=0;i<Math.min(rows.length(),4);i++)if(id.equals(rows.getJSONObject(i).optString("task")))return true;
        }catch(Exception ignored){}
        return false;
    }
    static void enqueue(Context context,JevScreenDecision decision) throws Exception {
        if(decision.expired() || !decision.actionable || !decision.receipt.optBoolean("generated")) throw new IllegalStateException("Suggestion is no longer valid. Generate it again");
        BackgroundScreenTasks owner=get(context);
        owner.pruneFinished();
        if(owner.jobs.size()>=4) throw new IllegalStateException("Too many background tasks. Complete or stop existing tasks first");
        Job job=new Job(UUID.randomUUID().toString(),decision.suggestion.split("\n",2)[0],System.currentTimeMillis());
        job.capture=decision;owner.jobs.put(job.id,job);
        try { owner.persist();owner.surfaces.connect();if(owner.connected)owner.progress(job); }
        catch(Exception failure) { owner.jobs.remove(job.id);owner.persist();throw failure; }
    }
    static void restore(Context context) {
        if(current!=null)return;
        BackgroundScreenTasks owner=get(context);
        try {
            if(!owner.saved.isFile())return;
            JSONArray rows=new JSONArray(new String(Files.readAllBytes(owner.saved.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(int i=0;i<Math.min(rows.length(),4);i++) {
                JSONObject row=rows.getJSONObject(i);
                if(!row.optString("state").equals("running") || !AgentRepository.validId(row.optString("thread")) || !row.optString("task").matches("[0-9]+"))continue;
                Job job=new Job(row.getString("id"),row.getString("title"),row.getLong("createdAt"));
                if(!AgentRepository.validId(job.id))continue;
                job.thread=row.getString("thread");job.task=row.getString("task");job.state="running";owner.jobs.put(job.id,job);
            }
            if(!owner.jobs.isEmpty())owner.surfaces.connect();
        } catch(Exception ignored) { /* Never replay an uncommitted capture after process death. */ }
    }
    private synchronized void persist() throws Exception {
        JSONArray rows=new JSONArray();
        for(Job job:jobs.values())rows.put(new JSONObject().put("id",job.id).put("title",job.title).put("createdAt",job.createdAt)
            .put("thread",job.thread).put("task",job.task).put("state",job.state));
        TaskRequests.write(saved,rows.toString());
    }
    private void pruneFinished() {
        for(Job job:new ArrayList<>(jobs.values()))if(!job.state.equals("starting") && !job.state.equals("running")) {
            dismiss(job.surfaceId());jobs.remove(job.id);
        }
    }
    @Override public void onConnected() {
        connected=true;
        for(Job job:jobs.values())progress(job);
        main.removeCallbacks(poll);main.post(poll);
    }
    @Override public void onEvent(String type,String id,long revision,String value) {
        if(type.equals("disconnected")) { connected=false;return; }
        if(type.equals("error")) {
            Toast.makeText(context,"The bottom component is temporarily unavailable. No automatic answer or confirmation was given",Toast.LENGTH_LONG).show();
            for(Job job:jobs.values())if(job.surfaceId().equals(id) && job.task.isEmpty()) {job.state="failed";job.capture=null;}
        }
        if(type.equals("published"))for(Job job:jobs.values())if(job.surfaceId().equals(id) && job.state.equals("starting") && !job.starting)start(job);
        if(type.equals("dismissed") || type.equals("expired")) {
            for(Question question:questions.values())if(question.surfaceId().equals(id)) {question.hidden=true;question.job.hidden=false;progress(question.job);}
            for(Job job:jobs.values())if(job.surfaceId().equals(id))job.hidden=true;
        }
    }
    @Override public void onAction(SurfaceClient.ActionEvent event) {
        for(Question question:questions.values())if(question.surfaceId().equals(event.id()) && question.revision==event.revision()) {
            if(question.answering)return;
            try { JSONObject response=question.presentation.response(event.actionId(),event.revision(),question.revision);
                if(response==null)return;
                question.answering=true;
                NativeClient.async(new JSONObject().put("op","respond").put("key",question.key)
                .put("response",response),new NativeClient.Callback() {
                    public void ready(JSONObject value) { dismiss(question.surfaceId());questions.remove(question.key);progress(question.job); }
                    public void failed(String message) { question.answering=false;showQuestion(question); }
                }); } catch(Exception error) { question.answering=false; }
            return;
        }
        for(Job job:jobs.values())if(job.surfaceId().equals(event.id()) && job.revision==event.revision()) {
            if(event.actionId().equals("stop"))cancel(job);
            else if(event.actionId().equals("resume")) {
                for(Question question:questions.values())if(question.job==job) {question.hidden=false;showQuestion(question);}
            }
            else if(event.actionId().equals("done")) {dismiss(job.surfaceId());jobs.remove(job.id);try{persist();}catch(Exception ignored){}}
            return;
        }
    }
    private void progress(Job job) {
        if(!connected || job.hidden)return;
        try {
            boolean running=job.state.equals("starting") || job.state.equals("running");
            boolean waiting=questions.values().stream().anyMatch(q->q.job==job);
            String title=running?(waiting?"Taku needs your choice":"Taku is working in the background"):job.state.equals("completed")?"Results are ready":job.state.equals("cancelled")?"Task cancelled":"This attempt did not complete";
            List<SurfaceSpec.Action> actions=new ArrayList<>();
            if(waiting)actions.add(new SurfaceSpec.Action("resume","Answer question",true));
            actions.add(new SurfaceSpec.Action(running?"stop":"done",running?"Stop task":"Got it",!waiting));
            String summary=running?job.title:limit(job.result,500);
            surfaces.publish(new SurfaceSpec(job.surfaceId(),++job.revision,"hint","bottom",title,summary,List.of(),actions,1800000,"compact","",null));
        } catch(Exception failure) { Toast.makeText(context,"The bottom component is temporarily unavailable",Toast.LENGTH_SHORT).show(); }
    }
    private void start(Job job) {
        job.starting=true;
        NativeClient.io.execute(()->{
            try {
                JevScreenDecision capture=job.capture;
                if(job.cancelRequested)throw new IllegalStateException("This background task was cancelled");
                if(capture==null || capture.expired())throw new IllegalStateException("Suggestion expired. Open the assistant again");
                ImageScreenSuggestion.requireCurrent(capture);
                if(!capture.receipt.optBoolean("userTriggered") && (!context.getSharedPreferences("agent-settings",0).getBoolean("jevEnabled",false)
                    ||!capture.receipt.optString("gateModel").equals(context.getSharedPreferences("agent-settings",0).getString("jevModel",""))))throw new IllegalStateException("Jev settings changed");
                JSONObject thread=NativeClient.rpcSync("thread/start",new JSONObject().put("developerInstructions",INSTRUCTIONS));
                job.thread=thread.getJSONObject("thread").getString("id");
                if(job.cancelRequested)throw new IllegalStateException("This background task was cancelled");
                JSONObject options=new JSONObject().put("includeScreenshot",true).put("responseStyle","concise");
                ComposerPreferences.apply(context.getSharedPreferences("agent-settings",0),job.thread,options);options.remove("attachments");
                TaskRequests.submit(capture.prompt(),capture.foreground,capture.screenshot,job.thread,options,id->{
                    if(job.cancelRequested)throw new IllegalStateException("This background task was cancelled");
                    job.task=id;job.state="running";persist();
                });
                job.capture=null;
                main.post(()->{progress(job);main.removeCallbacks(poll);main.post(poll);});
            } catch(Exception error) {
                job.state=job.cancelRequested?"cancelled":"failed";job.capture=null;job.result=error.getMessage()==null?"Background task did not start. Trigger it again":error.getMessage();
                try{persist();}catch(Exception ignored){}
                main.post(()->progress(job));
            }
        });
    }
    private final Runnable poll=new Runnable() { public void run() {
        for(Job job:jobs.values())if(job.state.equals("running") && !job.polling) {
            job.polling=true;
            NativeClient.io.execute(()->{
                try {
                    File task=new File(AssistantSession.TASKS,job.task);
                    JSONObject state=new JSONObject(new String(Files.readAllBytes(new File(task,"state.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
                    JSONObject nativeState=NativeClient.state(job.thread);
                    String result="";
                    if(List.of("completed","failed","cancelled","needs_user").contains(state.optString("state"))) {
                        File view=new File(AssistantSession.ROOT,"agent-view/"+job.thread+".json");
                        if(view.isFile() && view.length()<20000000)result=AgentConversation.lastReply(new JSONObject(new String(Files.readAllBytes(view.toPath()),java.nio.charset.StandardCharsets.UTF_8)));
                    }
                    final String reply=result;
                    main.post(()->update(job,state,nativeState,reply));
                } catch(Exception error) { main.post(()->job.polling=false); }
            });
        }
        if(jobs.values().stream().anyMatch(job->job.state.equals("running")))main.postDelayed(this,1500);
    }};
    private void update(Job job,JSONObject state,JSONObject nativeState,String reply) {
        job.polling=false;
        JSONArray pending=nativeState.optJSONArray("requests");List<String> present=new ArrayList<>();
        if(pending!=null)for(int i=0;i<pending.length();i++)try {
            JSONObject request=pending.getJSONObject(i),params=request.getJSONObject("params");
            String method=request.optString("method");
            if(!BackgroundQuestion.supports(method)
                || !job.thread.equals(params.optString("threadId")))continue;
            String key=request.getString("key");present.add(key);
            if(!questions.containsKey(key)) {
                android.os.LocaleList locales=context.getResources().getConfiguration().getLocales();
                java.util.Locale locale=locales.isEmpty()?java.util.Locale.getDefault():locales.get(0);
                Question question=new Question(job,key,BackgroundQuestion.fromPending(method,params,locale));
                questions.put(key,question);job.hidden=false;progress(job);showQuestion(question);
            }
        } catch(Exception invalid) { /* Invalid questions are never accepted on the user's behalf. */ }
        boolean questionsRemoved=false;
        for(Question question:new ArrayList<>(questions.values()))if(question.job==job && !present.contains(question.key)) {
            dismiss(question.surfaceId());questions.remove(question.key);questionsRemoved=true;
        }
        String status=state.optString("state");
        if(List.of("completed","failed","cancelled","needs_user").contains(status)) {
            job.state=status;job.result=BackgroundTaskResult.message(status,state.optString("message"),reply);job.hidden=false;
            try{persist();}catch(Exception ignored){}progress(job);
        } else if(System.currentTimeMillis()-job.createdAt>1800000)cancel(job);
        // Refresh once after an external answer, without covering a replacement question.
        else if(questionsRemoved && (job.state.equals("running") || job.state.equals("starting"))
            && questions.values().stream().noneMatch(q->q.job==job && !q.hidden))progress(job);
    }
    private void showQuestion(Question question) {
        if(!connected || question.hidden)return;
        try {
            surfaces.publish(question.presentation.surface(question.surfaceId(),++question.revision));
        } catch(Exception error) { Toast.makeText(context,"The question was not displayed. The task is still waiting",Toast.LENGTH_LONG).show(); }
    }
    private void cancel(Job job) {
        job.cancelRequested=true;
        NativeClient.io.execute(()->{try {
            if(!job.task.isEmpty())TaskRequests.write(new File(new File(AssistantSession.TASKS,job.task),"cancel"),"1");
            main.post(()->{if(job.task.isEmpty())job.state="cancelled";job.result="Background task stop requested";progress(job);});
        }catch(Exception error){main.post(()->Toast.makeText(context,"Stopping is not yet confirmed. Retry later",Toast.LENGTH_LONG).show());}});
    }
    private void dismiss(String id) { if(connected)try{surfaces.dismiss(id);}catch(Exception ignored){} }
    private static String limit(String value,int size) { return value.length()<=size?value:value.substring(0,size-1)+"…"; }
}
