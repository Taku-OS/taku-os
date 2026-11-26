package org.agentos.assistant;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host storage adapter. Policy, transactions' caller, projections, receipts and runtime are production
 * code; this adapter supplies detached JSON records instead of Android's SQLite connection. */
final class MatterStore implements MatterRetention.Storage {
    static final int MAX_MATTERS=256,MAX_SOURCES=256,MAX_RULES=64,MAX_COMMANDS=64,MAX_COMMAND_HISTORY=512;
    interface Work<T> {T run() throws Exception;}
    private JSONObject tables=new JSONObject();
    private JSONObject links=new JSONObject();
    private final File file;
    private final MatterReceiptHistory history;
    private int transactions;
    int fullScans;
    MatterStore(Context context) {
        file=new File(context.getFilesDir(),"host-matters.json");
        history=new MatterReceiptHistory(new File(context.getFilesDir(),"agent-ui-receipts"));
        try {
            if(file.isFile()) {JSONObject saved=new JSONObject(Files.readString(file.toPath()));tables=saved.getJSONObject("tables");links=saved.getJSONObject("links");}
            for(String name:List.of("matters","sources","rules","commands","occurrences","panel_records","panel_updates","panel_receipts","panel_directory","card_view_states","card_view_receipts","primary_snapshots","card_state_migrations","display_supersessions"))if(!tables.has(name))tables.put(name,new JSONObject());
            if(!tables.getJSONObject("panel_directory").has("directory"))tables.getJSONObject("panel_directory").put("directory",new JSONObject().put("listRevision",0).put("cursorSecret",java.util.UUID.randomUUID().toString()));
            archiveCardViewReceipts();
        }catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    <T> T transaction(Work<T> work) throws Exception {
        if(transactions==0)archiveCardViewReceipts();
        JSONObject before=new JSONObject(tables.toString()),beforeLinks=new JSONObject(links.toString());transactions++;
        try {
            T result=work.run();
            if(transactions==1)TaskRequests.write(file,new JSONObject().put("tables",tables).put("links",links).toString());
            return result;
        } catch(Exception failure) {tables=before;links=beforeLinks;throw failure;}
        finally {transactions--;}
    }
    private void persist() throws Exception {
        if(transactions==0)TaskRequests.write(file,new JSONObject().put("tables",tables).put("links",links).toString());
    }
    JSONObject get(String table,String id) throws Exception {
        JSONObject value=tables.getJSONObject(table).optJSONObject(id);return value==null?null:new JSONObject(value.toString());
    }
    JSONObject commandReceipt(String id) throws Exception {JSONObject live=get("commands",id);return live==null?history.command(id):live;}
    JSONObject cardViewReceipt(String id) throws Exception {JSONObject live=get("card_view_receipts",id);return live==null?history.cardView(id):live;}
    boolean hasCardViewReceipt(String id) throws Exception {return tables.getJSONObject("card_view_receipts").has(id)||history.hasCardView(id);}
    private void archiveCardViewReceipts() throws Exception {
        if(transactions!=0)throw new IllegalStateException("Cannot archive uncommitted receipts");
        JSONObject receipts=tables.getJSONObject("card_view_receipts");
        for(String id:new ArrayList<>(receipts.keySet())) {
            JSONObject receipt=receipts.getJSONObject(id);history.archiveCardView(id,receipt);
            receipts.remove(id);
            try {persist();}catch(Exception failure) {receipts.put(id,receipt);throw failure;}
        }
    }
    public JSONArray all(String table) throws Exception {
        fullScans++;
        JSONArray result=new JSONArray();JSONObject values=tables.getJSONObject(table);
        for(String id:values.keySet())result.put(new JSONObject(values.getJSONObject(id).toString()));
        return result;
    }
    JSONArray related(String table,String field,String id) throws Exception {
        JSONArray result=new JSONArray(),all=all(table);String property=field.equals("matter")?"matterId":"ruleId";
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString(property)))result.put(all.getJSONObject(i));
        return result;
    }
    void put(String table,String id,JSONObject value) throws Exception {
        int max=table.equals("matters")?MAX_MATTERS:table.equals("sources")?MAX_SOURCES:Integer.MAX_VALUE;
        if(get(table,id)==null&&count(table)>=max)throw new IOException("MATTER_STORAGE_FULL");
        int maximum=Set.of("primary_snapshots","panel_updates","card_view_states","card_view_receipts").contains(table)?8*1024*1024:128_000;
        if(value.toString().getBytes(StandardCharsets.UTF_8).length>maximum)throw new IOException("MATTER_TOO_LARGE");
        if(table.equals("panel_records"))for(String key:tables.getJSONObject(table).keySet())if(!key.equals(id)&&tables.getJSONObject(table).getJSONObject(key).getString("matterId").equals(value.getString("matterId")))throw new IOException("SOURCE_ALREADY_PINNED");
        JSONObject saved=new JSONObject(value.toString());
        if(table.equals("panel_updates")){JSONObject prior=get(table,id);saved.put("_rowid",prior==null?count(table)+1:prior.getLong("_rowid"));}
        tables.getJSONObject(table).put(id,saved);persist();
    }
    long count(String table) throws Exception {return tables.getJSONObject(table).length();}
    void remove(String table,String id) throws Exception {tables.getJSONObject(table).remove(id);persist();}
    boolean hasPendingPanelUpdates() throws Exception {return pendingPanelUpdates().length()>0;}
    JSONArray pendingPanelUpdates() throws Exception {
        JSONArray result=new JSONArray(),rows=all("panel_updates");
        for(int i=0;i<rows.length();i++)if(!rows.getJSONObject(i).optBoolean("settled"))result.put(rows.getJSONObject(i));return result;
    }
    JSONObject panelUpdateForTask(String task) throws Exception {
        JSONArray rows=pendingPanelUpdates();for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("taskId").equals(task))return rows.getJSONObject(i);return null;
    }
    JSONObject latestPanelUpdate(String panel) throws Exception {
        JSONObject latest=null;JSONArray rows=all("panel_updates");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("panelId").equals(panel)&&(latest==null||row.getLong("_rowid")>latest.getLong("_rowid")))latest=row;}return latest;
    }
    JSONObject panelForMatter(String id) throws Exception {
        JSONArray rows=all("panel_records");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("matterId").equals(id))return rows.getJSONObject(i);return null;
    }
    JSONArray panelPage(long position,String id,int limit) throws Exception {
        List<JSONObject> rows=new ArrayList<>();JSONArray all=all("panel_records");
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(row.getLong("position")>position||row.getLong("position")==position&&row.getString("panelId").compareTo(id)>0)rows.add(row);}
        rows.sort(java.util.Comparator.comparingLong((JSONObject row)->row.getLong("position")).thenComparing(row->row.getString("panelId")));
        JSONArray result=new JSONArray();for(int i=0;i<Math.min(limit,rows.size());i++)result.put(rows.get(i));return result;
    }
    void bind(String matter,String source,String block) throws Exception {
        links.put(matter+"\n"+block,new JSONObject().put("matterId",matter).put("sourceId",source).put("blockId",block));persist();
    }
    public JSONArray allBindings() throws Exception {
        fullScans++;
        JSONArray result=new JSONArray();for(String id:links.keySet())result.put(new JSONObject(links.getJSONObject(id).toString()));return result;
    }
    JSONArray bindings(String field,String id) throws Exception {
        JSONArray result=new JSONArray(),all=allBindings();String property=field.equals("matter")?"matterId":"sourceId";
        for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString(property)))result.put(all.getJSONObject(i));return result;
    }
    public void removeMatter(String id) throws Exception {
        JSONArray commands=related("commands","matter",id);for(int i=0;i<commands.length();i++)archiveCommand(commands.getJSONObject(i));
        for(String key:new ArrayList<>(links.keySet()))if(id.equals(links.getJSONObject(key).getString("matterId")))links.remove(key);
        tables.getJSONObject("matters").remove(id);persist();
    }
    public void removeSource(String id) {
        try {tables.getJSONObject("sources").remove(id);persist();}catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    void archiveCommand(JSONObject command) throws Exception {
        history.archiveCommand(command);tables.getJSONObject("commands").remove(command.getString("id"));persist();
    }
    void prune() throws Exception {
        JSONArray occurrences=all("occurrences");if(occurrences.length()<512)return;
        for(int i=0;i<occurrences.length();i++) {JSONObject occurrence=occurrences.getJSONObject(i);
            if(occurrence.optBoolean("terminal")&&occurrence.optLong("createdAt")<System.currentTimeMillis()-30L*86400_000)
                tables.getJSONObject("occurrences").remove(occurrence.getString("id"));}
        persist();
    }
}
final class SourceAdapters {
    interface Read {JSONObject read(JSONObject source) throws Exception;}
    volatile Read reader=source->new JSONObject().put("state","unavailable").put("observedAt",System.currentTimeMillis());
    SourceAdapters(Context context) { }
    SourceAdapter resolve(String kind) {
        return new SourceAdapter() {
            public JSONObject read(JSONObject source) throws Exception {return reader.read(source);}
            public AutoCloseable watch(JSONObject source,Runnable invalidated) {return ()->{};}
        };
    }
    JSONObject identity(String kind) throws Exception {return new JSONObject().put("kind",kind);}
    static JSONObject readObject(File file) throws Exception {return readObject(file,128_000);}
    static JSONObject readObject(File file,int maximum) throws Exception {if(!file.isFile()||file.length()>maximum)throw new IOException("SOURCE_UNAVAILABLE");return new JSONObject(Files.readString(file.toPath()));}
}
final class PublicSourceAdapter {
    static final class Failure extends IOException {long retryAfterAt;}
    static boolean supports(String kind) {return kind.equals("weather")||kind.equals("feed");}
    static String normalize(String kind,String resource) {return resource;}
}
final class MatterJob {static void schedule(Context context,long due,boolean pendingPanels) { }}

final class PanelNativeServices {
    final AgentGatewaySubmissions submissions;
    int publications;
    String availability="current";
    PanelNativeServices(Context context){submissions=AgentGatewaySubmissions.get(context.getFilesDir());}
    AgentGatewaySubmissions getSubmissions(){return submissions;}
    JSONObject reply(JSONObject input){return AgentGatewayProtocol.replyReadError("REPLY_UNAVAILABLE");}
    String contractHash(){return "a".repeat(64);}
    String threadState(String thread){return availability;}
    void checkSubmit(String thread) throws IOException {if(availability.equals("thread-unavailable"))throw new IOException("SOURCE_THREAD_NOT_FOUND");}
    void checkScreenshot(JSONObject input) throws IOException {
        JSONObject options=input.optJSONObject("options");
        if(options!=null&&options.optBoolean("includeScreenshot")&&!input.optString("requestId").equals(options.optString("invocationScreenshot")))
            throw new IOException("SCREENSHOT_UNAVAILABLE");
    }
    JSONObject submit(JSONObject input) throws Exception {
        return submissions.submit(input.getString("requestId"),input.getString("threadId"),input.getString("text"),()->checkSubmit(input.getString("threadId")),before->publish(input,before));
    }
    String publish(JSONObject input,TaskRequests.BeforePublish before) throws Exception {
        String id=Long.toString(System.nanoTime());File dir=new File(TaskRuntime.TASKS,id);dir.mkdirs();
        JSONObject request=new JSONObject().put("id",id).put("conversationId",input.getString("threadId")).put("prompt",input.getString("text"));
        if(input.has("panelUpdate"))request.put("panelUpdate",input.getJSONObject("panelUpdate"));
        if(input.has("panelUpdate"))org.agentos.lab.ComposerInput.copyPanelContext(input,request);
        TaskRequests.write(new File(dir,"request.json"),request.toString());
        TaskRequests.write(new File(dir,"state.json"),new JSONObject().put("id",id).put("state","queued").toString());
        before.persist(id);TaskRequests.write(new File(dir,"ready"),"1");publications++;return id;
    }
    void notifyDirectory(){}
    void notifyMatter(String id){}
    void open(String id){}
    JSONObject navigation(){return new JSONObject().put("available",new JSONArray()).put("visible",new JSONArray()).put("selectedPanelId",JSONObject.NULL);}
}
final class ManualExecutor extends AbstractExecutorService {
    private final Queue<Runnable> queue=new ArrayDeque<>();
    private boolean shutdown;
    public synchronized void execute(Runnable work) {queue.add(work);}
    synchronized Runnable take() {return queue.remove();}
    void drain() {for(;;) {Runnable work;synchronized(this) {work=queue.poll();}if(work==null)return;work.run();}}
    public synchronized void shutdown() {shutdown=true;}
    public synchronized List<Runnable> shutdownNow() {shutdown=true;List<Runnable> work=new ArrayList<>(queue);queue.clear();return work;}
    public boolean isShutdown() {return shutdown;}
    public synchronized boolean isTerminated() {return shutdown&&queue.isEmpty();}
    public boolean awaitTermination(long timeout,TimeUnit unit) {return isTerminated();}
}
