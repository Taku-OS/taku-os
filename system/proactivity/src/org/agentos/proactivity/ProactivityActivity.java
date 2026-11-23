package org.agentos.proactivity;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;
import org.agentos.proactivity.api.*;
import static org.agentos.proactivity.ProactivityUi.*;

/** Host-owned consent, policy editing and explanations. Intent extras only navigate. */
public final class ProactivityActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private ProactivityUi ui;
    private LinearLayout header,body,nav;
    private ScrollView scroll;
    private boolean bound,deepLink,technical,history;
    private int tab;
    private String selected="",rendered="";
    private Dialog policyEditor;
    private EditText[] policyInputs;
    private String editKey="";
    private long editRevision;
    private long selectedHour;
    private final int[] positions=new int[3];
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){render(false);}
        public void onServiceDisconnected(ComponentName n){}
    };
    private final Runnable refresh=new Runnable(){public void run(){render(false);main.postDelayed(this,1200);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ui=new ProactivityUi(this);
        if(state!=null){tab=state.getInt("tab");selected=state.getString("selected","");selectedHour=state.getLong("selectedHour");deepLink=state.getBoolean("deepLink");technical=state.getBoolean("technical");history=state.getBoolean("history");int[] p=state.getIntArray("positions");if(p!=null)System.arraycopy(p,0,positions,0,3);}
        else readLink(getIntent());
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView();getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout root=ui.col();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        header=ui.col();root.addView(header);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=ui.col();body.setPadding(ui.dp(22),ui.dp(8),ui.dp(22),ui.dp(28));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=ui.col();root.addView(nav);setContentView(root);root.requestApplyInsets();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0,this::back);
        render(true);
        if(state!=null){
            scroll.post(()->scroll.scrollTo(0,state.getInt("scroll")));
            String restore=state.getString("editKey","");String[] values=state.getStringArray("editValues");
            if(!restore.isEmpty())main.post(()->change(()->{
                HostRuntime runtime=HostRuntime.get(this);JSONObject row=runtime.registry.item(restore);
                if(row.getLong("activeRevision")!=state.getLong("editRevision") || row.getLong("pendingRevision")>0)throw new IllegalStateException("Policy changed. Reopen it before editing");
                editPolicy(runtime,restore,row,RegistryStore.activeSpec(row));
                if(values!=null&&values.length==4)for(int i=0;i<4;i++)policyInputs[i].setText(values[i]);
            }));
        }
    }
    private void readLink(Intent intent){String id=intent.getStringExtra("observerId");if(id!=null&&id.length()<=120){selected=id;tab=1;deepLink=true;}}
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readLink(intent);render(true);}
    @Override public void onSaveInstanceState(Bundle state){
        state.putInt("scroll",scroll.getScrollY());
        state.putLong("selectedHour",selectedHour);
        if(policyEditor!=null&&policyEditor.isShowing()){
            state.putString("editKey",editKey);state.putLong("editRevision",editRevision);
            String[] values=new String[4];for(int i=0;i<4;i++)values[i]=policyInputs[i].getText().toString();state.putStringArray("editValues",values);
        }
        positions[tab]=scroll.getScrollY();state.putInt("tab",tab);state.putString("selected",selected);state.putBoolean("deepLink",deepLink);state.putBoolean("technical",technical);state.putBoolean("history",history);state.putIntArray("positions",positions);super.onSaveInstanceState(state);}
    @Override public void onStart(){super.onStart();bound=bindService(new Intent(this,ProactivityService.class),connection,BIND_AUTO_CREATE);render(false);main.postDelayed(refresh,1200);}
    @Override public void onStop(){main.removeCallbacks(refresh);if(bound)unbindService(connection);bound=false;super.onStop();}
    @Override public void onDestroy(){if(policyEditor!=null)policyEditor.dismiss();super.onDestroy();}
    private void back(){if(!selected.isEmpty()&&!deepLink){selected="";technical=history=false;render(true);}else finish();}
    private interface Change {void run()throws Exception;}
    private void change(Change action){try{action.run();render(false);}catch(Exception e){new AlertDialog.Builder(this).setTitle("Cannot complete right now").setMessage(e.getMessage()).setPositiveButton("Got it",null).show();}}
    private void open(String key){positions[tab]=scroll.getScrollY();selected=key;technical=history=false;deepLink=false;render(true);}
    private void switchTab(int next){if(selected.isEmpty())positions[tab]=scroll.getScrollY();selected="";tab=next;technical=history=deepLink=false;render(true);}
    private void render(boolean navigation){
        HostRuntime runtime=HostRuntime.get(this);
        try {
            JSONObject items=runtime.registry==null?new JSONObject():runtime.registry.items();
            // Deep links may name an observer ID, but never confer permission to mutate it.
            if(!selected.isEmpty()&&!items.has(selected))for(String key:RegistryStore.keys(items))if(items.getJSONObject(key).getString("id").equals(selected)){selected=key;break;}
            if(!selected.isEmpty()&&!items.has(selected)){selected="";deepLink=false;}
            String fingerprint=items.toString()+runtime.failure+tab+selected+technical+history
                    +(tab==0?System.currentTimeMillis()/OverviewStats.HOUR+java.time.ZoneId.systemDefault().getId():"");
            if(!navigation&&fingerprint.equals(rendered))return;
            int y=navigation?(selected.isEmpty()?positions[tab]:0):scroll.getScrollY();rendered=fingerprint;
            header.removeAllViews();body.removeAllViews();nav.removeAllViews();
            renderHeader();
            if(runtime.registry==null)empty("shield","Configuration unavailable","Existing configuration was not reset. Reopen the app and retry.\n"+runtime.failure);
            else if(!selected.isEmpty())detail(runtime,selected,items.getJSONObject(selected));
            else if(tab==0)overview(items);
            else if(tab==1)observers(items);
            else activity(items);
            if(!runtime.failure.isEmpty()&&runtime.registry!=null){ui.add(body,ui.text("Some hints are temporarily unavailable. Inspect the runtime in integration details.",12,MUTED),18);}
            renderNav();scroll.post(()->scroll.scrollTo(0,y));
        }catch(Exception e){body.removeAllViews();empty("shield","Cannot read right now","Keep the configuration and retry later.\n"+e.getMessage());}
    }
    private void renderHeader(){
        if(!selected.isEmpty()){
            LinearLayout row=ui.row();row.setPadding(ui.dp(8),ui.dp(6),ui.dp(22),ui.dp(6));row.addView(ui.iconButton("back","Back",this::back));row.addView(ui.bold("Observer details",17,INK),new LinearLayout.LayoutParams(0,-2,1));row.addView(ui.brand());header.addView(row);return;
        }
        LinearLayout row=ui.row();row.setPadding(ui.dp(22),ui.dp(20),ui.dp(22),ui.dp(18));
        LinearLayout copy=ui.col();copy.addView(ui.bold(new String[]{"Proactivity","Observers","Activity log"}[tab],30,INK));ui.add(copy,ui.text(new String[]{"Make every proactive action visible","Chosen by you, maintained by developers","Every hint leaves a trace"}[tab],13,MUTED),7);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));row.addView(ui.brand());header.addView(row);
    }
    private void renderNav(){
        nav.setVisibility(selected.isEmpty()?View.VISIBLE:View.GONE);nav.setBackgroundColor(Color.WHITE);ui.divider(nav,0);
        LinearLayout row=ui.row();String[] labels={"Overview","Observers","Activity log"},icons={"spark","observers","clock"};
        for(int i=0;i<3;i++){final int next=i;LinearLayout item=ui.col();item.setGravity(Gravity.CENTER);item.setPadding(0,ui.dp(11),0,ui.dp(10));item.setMinimumHeight(ui.dp(64));int color=tab==i?ORANGE:MUTED;item.addView(ui.icon(icons[i],color,23));TextView label=ui.text(labels[i],11,color);ui.add(item,label,6);label.setGravity(Gravity.CENTER);item.setBackground(ui.touch(Color.TRANSPARENT,0,0));item.setContentDescription(labels[i]);item.setSelected(tab==i);item.setFocusable(true);item.setOnClickListener(v->switchTab(next));row.addView(item,new LinearLayout.LayoutParams(0,-2,1));}nav.addView(row);
    }
    private void heading(String title,String action,Runnable click){LinearLayout row=ui.row();row.setPadding(0,ui.dp(22),0,ui.dp(12));row.addView(ui.bold(title,17,INK),new LinearLayout.LayoutParams(0,-2,1));if(action!=null){TextView link=ui.bold(action,13,ORANGE);link.setPadding(ui.dp(10),ui.dp(12),0,ui.dp(12));link.setMinHeight(ui.dp(48));link.setMinWidth(ui.dp(48));link.setGravity(Gravity.CENTER);link.setOnClickListener(v->click.run());row.addView(link);}body.addView(row);}
    private OverviewStats overviewStats(JSONObject items)throws Exception{
        List<OverviewStats.Source> sources=new ArrayList<>();
        for(String key:RegistryStore.keys(items)){
            JSONObject row=items.getJSONObject(key);List<OverviewStats.Event> events=new ArrayList<>();JSONArray trace=row.getJSONArray("trace");
            for(int i=0;i<trace.length();i++){JSONObject event=trace.getJSONObject(i),details=event.optJSONObject("details");events.add(new OverviewStats.Event(event.getLong("at"),event.getString("type"),details==null?"":details.optString("candidateId")));}
            sources.add(new OverviewStats.Source(key,ProactivityWire.spec(shown(row).getJSONObject("spec")).name(),RegistryStore.active(row),row.getLong("pendingRevision")>0,events));
        }
        return new OverviewStats(sources,System.currentTimeMillis(),java.time.ZoneId.systemDefault());
    }
    private String chartTime(OverviewStats stats,long wall,String pattern){return java.time.format.DateTimeFormatter.ofPattern(pattern,Locale.ROOT).withZone(stats.zone).format(java.time.Instant.ofEpochMilli(wall));}
    private void overview(JSONObject items)throws Exception{
        OverviewStats stats=overviewStats(items);int initial=stats.selected(selectedHour);
        if(stats.pending>0){
            LinearLayout notice=ui.row();notice.setPadding(ui.dp(14),ui.dp(4),ui.dp(8),ui.dp(4));notice.setMinimumHeight(ui.dp(48));notice.setBackground(ui.touch(SOFT,14,0));notice.addView(ui.icon("shield",ORANGE,18));
            TextView text=ui.bold(stats.pending+" integrations awaiting confirmation",13,INK);LinearLayout.LayoutParams copy=new LinearLayout.LayoutParams(0,-2,1);copy.leftMargin=ui.dp(9);notice.addView(text,copy);notice.addView(ui.icon("chevron",ORANGE,18));notice.setContentDescription("View pending observers");notice.setFocusable(true);notice.setOnClickListener(v->switchTab(1));ui.add(body,notice,0);ui.space(body,14);
        }
        int pale=0xffc0cbbb;LinearLayout hero=ui.card();hero.setBackground(ui.shape(0xff1c2820,22,0));
        LinearLayout top=ui.row();top.addView(ui.bold("Activity heatmap",14,Color.WHITE),new LinearLayout.LayoutParams(0,-2,1));top.addView(ui.badge("24 hours",0xffd9e3d4,0xff304035));hero.addView(top);
        LinearLayout metric=ui.row();metric.setGravity(Gravity.BOTTOM);metric.addView(ui.bold(String.valueOf(stats.activity),44,Color.WHITE));TextView unit=ui.text("retained activities",12,pale);LinearLayout.LayoutParams unitP=new LinearLayout.LayoutParams(-2,-2);unitP.leftMargin=ui.dp(10);unitP.bottomMargin=ui.dp(7);metric.addView(unit,unitP);ui.add(hero,metric,17);
        ui.add(hero,ui.text(chartTime(stats,stats.start,"MM.dd HH:mm")+" — "+chartTime(stats,stats.now,"MM.dd HH:mm"),11,pale),8);
        OverviewCharts.Heatmap[] heat=new OverviewCharts.Heatmap[1];OverviewCharts.Trend[] trend=new OverviewCharts.Trend[1];TextView hourLabel=ui.bold("",13,Color.WHITE),hourCounts=ui.text("",11,pale),trendCaption=ui.text("",12,MUTED);
        java.util.function.IntConsumer select=hour->{
            int h=Math.max(0,Math.min(23,hour));selectedHour=stats.at(h);heat[0].select(h);trend[0].select(h);
            hourLabel.setText(chartTime(stats,stats.at(h),"MM.dd HH:mm")+"–"+chartTime(stats,stats.at(h)+OverviewStats.HOUR,"HH:mm"));
            hourCounts.setText("Policy "+stats.hourly[0][h]+" · Hints "+stats.hourly[1][h]+" · Viewed "+stats.hourly[2][h]+" · Other "+stats.hourly[3][h]);
            int pair=h/2;trendCaption.setText(chartTime(stats,stats.at(pair*2),"MM.dd HH:mm")+"–"+chartTime(stats,stats.at(pair*2)+2*OverviewStats.HOUR,"HH:mm")+" · Displayed "+trend[0].offers[pair]+" times, viewed "+trend[0].views[pair]+" times");
        };
        heat[0]=new OverviewCharts.Heatmap(this,stats,select);trend[0]=new OverviewCharts.Trend(this,stats,select);ui.add(hero,heat[0],18);
        LinearLayout selection=ui.row();TextView prev=hourButton("‹","Previous hour",()->select.accept(stats.selected(selectedHour)-1)),next=hourButton("›","Next hour",()->select.accept(stats.selected(selectedHour)+1));selection.addView(prev);hourLabel.setGravity(Gravity.CENTER);selection.addView(hourLabel,new LinearLayout.LayoutParams(0,-2,1));selection.addView(next);ui.add(hero,selection,2);hourCounts.setGravity(Gravity.CENTER);ui.add(hero,hourCounts,5);ui.add(body,hero,0);
        ui.add(body,ui.text("Existing records grouped by hour; each observer retains at most 16. Empty cells mean no retained records.",11,MUTED),10);
        heading("Hint trends","Activity log",()->switchTab(2));LinearLayout trends=ui.card();LinearLayout totals=ui.row();chartTotal(totals,stats.published,"Hints displayed",ORANGE);chartTotal(totals,stats.accepted,"Hints viewed",GREEN);trends.addView(totals);ui.add(trends,trend[0],18);ui.add(trends,trendCaption,9);ui.add(trends,ui.text("Orange bars: displayed · Green line: viewed · 2 hours per cell",11,MUTED),9);ui.add(body,trends,0);
        heading("Observer status","Manage",()->switchTab(1));LinearLayout status=ui.card(),statusRow=ui.row();statusRow.addView(new OverviewCharts.Donut(this,stats),new LinearLayout.LayoutParams(ui.dp(132),ui.dp(136)));LinearLayout states=ui.col();statusLine(states,"Enabled",stats.active,GREEN);ui.space(states,17);statusLine(states,"Pending",stats.pending,ORANGE);ui.space(states,17);statusLine(states,"Disabled",stats.disabled,0xff9eaa99);LinearLayout.LayoutParams stateP=new LinearLayout.LayoutParams(0,-2,1);stateP.leftMargin=ui.dp(20);statusRow.addView(states,stateP);status.addView(statusRow);ui.add(status,ui.text("Authorization status comes from current configuration; enabled plugins receive events while connected.",11,MUTED),14);ui.add(body,status,0);
        if(stats.totalSources>0){heading("Active observers",null,null);LinearLayout ranking=ui.card();int count=0,max=Math.max(1,stats.ranking.get(0).count());for(OverviewStats.Ranked source:stats.ranking){if(count++>=3)break;if(count>1)ui.space(ranking,20);LinearLayout item=ui.col();LinearLayout title=ui.row();TextView name=ui.bold(source.name(),14,INK);title.addView(name,new LinearLayout.LayoutParams(0,-2,1));title.addView(ui.bold(source.count()+" records",13,MUTED));item.addView(title);ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(max);bar.setProgress(source.count());bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ORANGE));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,ui.dp(6));bp.topMargin=ui.dp(10);item.addView(bar,bp);item.setMinimumHeight(ui.dp(48));item.setBackground(ui.touch(Color.WHITE,8,0));item.setContentDescription(source.name()+", retained in 24 hours: "+source.count()+" activities; view observer");item.setFocusable(true);item.setOnClickListener(v->open(source.key()));ranking.addView(item);}ui.add(ranking,ui.text("Ranked by retained activity in the same time range.",11,MUTED),18);ui.add(body,ranking,0);}
        else{heading("Connect your first observer",null,null);LinearLayout start=ui.card();start.addView(ui.bold("Start with an independent plugin",17,INK));ui.add(start,ui.text("Install and open an observer app, then review and approve it here. Its activity will appear in the charts.",13,MUTED),9);ui.add(start,ui.action("Developer integration guide",false,"developer-guide",this::developerGuide),18);ui.add(body,start,0);}
        heading("For developers","Integration guide",this::developerGuide);ui.add(body,ui.text("Independent apps, a public protocol, and an optional SDK. Each developer maintains their own observers.",13,MUTED),0);
        select.accept(initial);
    }
    private TextView hourButton(String text,String description,Runnable click){TextView button=ui.bold(text,26,0xffe9eee5);button.setGravity(Gravity.CENTER);button.setMinWidth(ui.dp(48));button.setMinHeight(ui.dp(48));button.setBackground(ui.touch(0xff304035,12,0));button.setContentDescription(description);button.setFocusable(true);button.setOnClickListener(v->click.run());return button;}
    private void chartTotal(LinearLayout row,int count,String name,int color){LinearLayout col=ui.col();LinearLayout label=ui.row();View dot=new View(this);dot.setBackground(ui.shape(color,3,0));label.addView(dot,new LinearLayout.LayoutParams(ui.dp(6),ui.dp(6)));TextView text=ui.text(name,12,MUTED);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=ui.dp(7);label.addView(text,lp);col.addView(label);ui.add(col,ui.bold(String.valueOf(count),30,INK),10);row.addView(col,new LinearLayout.LayoutParams(0,-2,1));}
    private void statusLine(LinearLayout parent,String label,int count,int color){LinearLayout row=ui.row();View dot=new View(this);dot.setBackground(ui.shape(color,4,0));row.addView(dot,new LinearLayout.LayoutParams(ui.dp(7),ui.dp(7)));TextView name=ui.text(label,13,MUTED);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=ui.dp(9);row.addView(name,lp);row.addView(ui.bold(String.valueOf(count),17,INK));parent.addView(row);}
    private void observers(JSONObject items)throws Exception{
        if(items.length()==0){empty("observers","No observers yet","Install and open an observer app.\nIts integration request will appear here.");ui.add(body,ui.action("Developer integration guide",false,"developer-guide",this::developerGuide),22);return;}
        ui.add(body,ui.text("Only approved observers can trigger hints. New or updated policies require your confirmation.",13,MUTED),0);ui.space(body,12);
        for(String key:RegistryStore.keys(items))observerCard(key,items.getJSONObject(key));
    }
    private JSONObject shown(JSONObject row)throws Exception{return RegistryStore.revision(row,row.getLong("pendingRevision")>0?row.getLong("pendingRevision"):row.getLong("activeRevision"));}
    private String status(JSONObject row){return row.optLong("pendingRevision")>0?(row.optLong("activeRevision")>0?"Update awaiting confirmation":"Awaiting approval"):row.optBoolean("enabled")?"Enabled":"Disabled";}
    private String appName(JSONObject row){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(row.getString("package"),0)).toString();}catch(Exception e){return row.optString("package");}}
    private void observerCard(String key,JSONObject row)throws Exception{
        ObserverSpec spec=ProactivityWire.spec(shown(row).getJSONObject("spec"));LinearLayout card=ui.card();card.setBackground(ui.touch(Color.WHITE,20,LINE));
        LinearLayout line=ui.row();LinearLayout copy=ui.col();copy.addView(ui.bold(spec.name(),17,INK));ui.add(copy,ui.text(appName(row),12,MUTED),7);line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));boolean pending=row.getLong("pendingRevision")>0;line.addView(ui.badge(status(row),pending?ORANGE:row.getBoolean("enabled")?GREEN:MUTED,pending?SOFT:BG));card.addView(line);
        ui.add(card,ui.text("Show a hint after changes settle for "+duration(spec.quietMs())+"",14,INK),17);ui.add(card,ui.text("At least "+duration(spec.cooldownMs())+" between hints · Up to "+spec.maxRecords()+" events",12,MUTED),7);
        card.setOnClickListener(v->open(key));card.setFocusable(true);card.setContentDescription(spec.name()+", from "+appName(row)+"，"+status(row));ui.add(body,card,10);
    }
    private void detail(HostRuntime runtime,String key,JSONObject row)throws Exception{
        JSONObject version=shown(row);ObserverSpec spec=ProactivityWire.spec(version.getJSONObject("spec"));long pending=row.getLong("pendingRevision"),active=row.getLong("activeRevision");
        LinearLayout stateLine=ui.row();stateLine.addView(ui.badge(status(row),pending>0?ORANGE:row.getBoolean("enabled")?GREEN:MUTED,pending>0?SOFT:row.getBoolean("enabled")?0xffeef4ec:LINE));ui.add(body,stateLine,14);
        ui.add(body,ui.bold(spec.name(),27,INK),16);ui.add(body,ui.text("From "+appName(row)+" · Plugin v"+installedVersion(row),13,MUTED),9);
        if(pending>0){LinearLayout consent=ui.card();consent.setBackground(ui.shape(SOFT,20,0));consent.addView(ui.bold(active>0?"Confirm this update":"Allow this observer?",17,INK));ui.add(consent,ui.text("After approval, it may report its declared page changes and display the hint below when changes settle. No new hints appear before an update is approved.",14,INK),10);ui.add(consent,ui.action(active>0?"Confirm update and enable":"Allow and enable",true,"approve-observer",()->change(()->runtime.approve(key,pending))),18);ui.add(body,consent,22);}
        heading("Hint preview",null,null);LinearLayout preview=ui.card();LinearLayout mark=ui.row();mark.addView(ui.icon("spark",ORANGE,19));TextView caption=ui.text("From this observer",12,MUTED);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.leftMargin=ui.dp(9);mark.addView(caption,cp);preview.addView(mark);ui.add(preview,ui.bold(spec.title(),17,INK),16);ui.add(preview,ui.text(spec.summary(),14,MUTED),9);
        if(spec.prompt()!=null){ui.add(preview,ui.text("Top card · "+spec.prompt().declineLabel()+" / "+spec.prompt().acceptLabel(),13,INK),16);}
        else ui.add(preview,ui.text("Viewed",13,INK),16);
        ui.add(body,preview,0);
        heading("Trigger policy",pending==0?"Edit":null,()->editPolicy(runtime,key,row,spec));LinearLayout policy=ui.card();value(policy,"Quiet period",duration(spec.quietMs()),"Wait this long after continuous changes stop.");ui.divider(policy,16);ui.space(policy,16);value(policy,"Hint interval",duration(spec.cooldownMs()),"Minimum interval between successful hints.");ui.divider(policy,16);ui.space(policy,16);value(policy,"Context range","Last "+duration(spec.lookbackMs())+" · Up to "+spec.maxRecords()+" records","Select only event metadata reported by this observer.");ui.add(body,policy,0);
        if(pending==0){ui.add(body,ui.action(row.getBoolean("enabled")?"Disable observer":"Enable observer",false,row.getBoolean("enabled")?"disable-observer":"approve-observer",()->change(()->{if(row.getBoolean("enabled"))runtime.disable(key);else runtime.approve(key,active);})),18);}
        heading("Version and maintenance",null,null);LinearLayout maintenance=ui.card();maintenance.addView(ui.bold(active>0?"Current policy #"+active:"No active policy",15,INK));ui.add(maintenance,ui.text("Developers update plugins independently. Policy rollback restores prior settings while retaining a new version.",13,MUTED),8);
        if(active>0&&canRollback(row)){ui.add(maintenance,ui.action("Roll back to previous policy",false,"rollback-policy",()->new AlertDialog.Builder(this).setTitle("Restore previous policy?").setMessage("Restore previous trigger parameters and hint text. The installed plugin app version stays the same.").setNegativeButton("Cancel",null).setPositiveButton("Restore policy",(d,w)->change(()->runtime.rollback(key,active))).show()),16);}
        ui.add(maintenance,ui.action(history?"Collapse version history":"View version history",false,"policy-history",()->{history=!history;render(false);}),12);
        if(history){JSONArray revisions=row.getJSONArray("history");for(int i=revisions.length()-1;i>=0;i--){JSONObject h=revisions.getJSONObject(i);ui.divider(maintenance,14);ui.add(maintenance,ui.text("Policy #"+h.getLong("revision")+(h.getLong("revision")==active?" · Current":"")+(h.getLong("revision")==pending?" · Pending":"")+(h.has("rollbackOf")?" · Restored from #"+h.getLong("rollbackOf"):h.optString("author").equals("user")?" · You edited parameters":" · Plugin registration"),13,INK),14);}}
        ui.add(body,maintenance,0);
        heading("Integration details",technical?"Collapse":"Expand",()->{technical=!technical;render(false);});
        if(technical){LinearLayout info=ui.card();value(info,"Provider",row.getString("package"),"Observer ID\n"+row.getString("id"));ui.add(info,ui.text("Event type\n"+spec.eventType()+"\n\nSignature SHA-256\n"+row.getString("signer")+"\n\nPublic protocol v1 · Independent process and UID",12,MUTED),18);info.setContentDescription("Integration details");ui.add(body,info,0);}
        TextView remove=ui.action("Remove observer",false,"remove-observer",()->new AlertDialog.Builder(this).setTitle("Remove this observer?").setMessage("Stop hints and delete this observer's authorization and policy history. Registration will require approval again.").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->change(()->{runtime.remove(key);selected="";deepLink=false;})).show());remove.setTextColor(0xffa33a27);ui.add(body,remove,16);
    }
    private long installedVersion(JSONObject row){try{return getPackageManager().getPackageInfo(row.getString("package"),0).getLongVersionCode();}catch(Exception e){return 0;}}
    private boolean canRollback(JSONObject row)throws Exception{JSONArray h=row.getJSONArray("history");for(int i=1;i<h.length();i++)if(h.getJSONObject(i).getLong("revision")==row.getLong("activeRevision"))return true;return false;}
    private void value(LinearLayout card,String title,String value,String explanation){card.addView(ui.text(title,12,MUTED));ui.add(card,ui.bold(value,16,INK),7);ui.add(card,ui.text(explanation,12,MUTED),7);}
    static String duration(long ms){if(ms<1000)return ms+" ms";if(ms%60000==0)return ms/60000+" minutes";return (ms%1000==0?String.valueOf(ms/1000):String.format(Locale.ROOT,"%.1f",ms/1000d))+" seconds";}
    private void editPolicy(HostRuntime runtime,String key,JSONObject row,ObserverSpec spec){
        // A full-height native editor keeps Save above the IME and scrolls the form independently.
        Dialog dialog=new Dialog(this,android.R.style.Theme_Material_Light_NoActionBar);
        policyEditor=dialog;editKey=key;editRevision=row.optLong("activeRevision");
        LinearLayout root=ui.col();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        LinearLayout bar=ui.row();bar.setPadding(ui.dp(8),ui.dp(6),ui.dp(22),ui.dp(6));
        bar.addView(ui.iconButton("back","Cancel",dialog::dismiss));bar.addView(ui.bold("Edit trigger policy",18,INK));root.addView(bar);
        ScrollView form=new ScrollView(this);form.setFillViewport(true);LinearLayout fields=ui.col();fields.setPadding(ui.dp(22),ui.dp(14),ui.dp(22),ui.dp(24));form.addView(fields);root.addView(form,new LinearLayout.LayoutParams(-1,0,1));
        fields.addView(ui.text("Adjust to your pace. Saving applies changes immediately and retains a policy version for rollback.",14,MUTED));
        String[] names={"Quiet period (0.1–10 seconds)","Hint interval (1–3600 seconds)","Maximum records (1–16)","Lookback range (1–600 seconds)"};
        String[] values={seconds(spec.quietMs()),seconds(spec.cooldownMs()),String.valueOf(spec.maxRecords()),seconds(spec.lookbackMs())};
        EditText[] inputs=new EditText[4];policyInputs=inputs;
        for(int i=0;i<4;i++){
            ui.add(fields,ui.bold(names[i],14,INK),24);EditText input=new EditText(this);input.setText(values[i]);input.setTextSize(18);input.setTextColor(INK);
            input.setInputType(InputType.TYPE_CLASS_NUMBER|(i==2?0:InputType.TYPE_NUMBER_FLAG_DECIMAL));input.setSingleLine();input.setMinHeight(ui.dp(54));input.setPadding(ui.dp(14),ui.dp(13),ui.dp(14),ui.dp(13));input.setBackground(ui.shape(Color.WHITE,12,LINE));
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|(i==3?android.view.inputmethod.EditorInfo.IME_ACTION_DONE:android.view.inputmethod.EditorInfo.IME_ACTION_NEXT));
            input.setOnFocusChangeListener((v,focus)->input.setBackground(ui.shape(Color.WHITE,12,focus?ORANGE:LINE)));
            input.setContentDescription(names[i]);inputs[i]=input;ui.add(fields,input,10);
        }
        LinearLayout footer=ui.col();footer.setPadding(ui.dp(22),ui.dp(12),ui.dp(22),ui.dp(16));footer.setBackgroundColor(BG);
        footer.addView(ui.action("Save settings",true,"save-policy",()->{
            try{
                ObserverSpec edited=new ObserverSpec(spec.id(),spec.name(),spec.eventType(),millis(inputs[0]),millis(inputs[1]),Integer.parseInt(inputs[2].getText().toString()),millis(inputs[3]),spec.title(),spec.summary(),spec.prompt());
                runtime.editPolicy(key,row.optLong("activeRevision"),edited);dialog.dismiss();render(false);
            }catch(Exception error){Toast.makeText(this,error instanceof IllegalStateException?error.getMessage():"Enter values within the shown ranges, with at most millisecond precision. Settings have not been saved.",Toast.LENGTH_LONG).show();}
        }));root.addView(footer);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets padding=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            boolean compact=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&insets.isVisible(WindowInsets.Type.ime());
            // Reclaim the header while the landscape IME is open so the focused field fits.
            // System Back dismisses the IME and restores the native Cancel header.
            bar.setVisibility(compact?View.GONE:View.VISIBLE);
            footer.setPadding(ui.dp(22),ui.dp(compact?8:12),ui.dp(22),ui.dp(compact?8:16));
            v.setPadding(padding.left,padding.top,padding.right,padding.bottom);return insets;
        });
        dialog.setContentView(root);Window window=dialog.getWindow();window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.setOnDismissListener(d->{if(policyEditor==dialog){policyEditor=null;policyInputs=null;editKey="";}});
        dialog.show();window.setLayout(-1,-1);window.getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        root.requestFocus();root.requestApplyInsets();
    }
    private String seconds(long ms){return java.math.BigDecimal.valueOf(ms,3).stripTrailingZeros().toPlainString();}
    private long millis(EditText input){return new java.math.BigDecimal(input.getText().toString()).multiply(java.math.BigDecimal.valueOf(1000)).longValueExact();}
    private record Entry(String key,JSONObject row,JSONObject trace){}
    private void activity(JSONObject items)throws Exception{
        List<Entry> entries=new ArrayList<>();for(String key:RegistryStore.keys(items)){JSONObject row=items.getJSONObject(key);JSONArray trace=row.getJSONArray("trace");for(int i=0;i<trace.length();i++)entries.add(new Entry(key,row,trace.getJSONObject(i)));}
        entries.sort((a,b)->Long.compare(b.trace.optLong("at"),a.trace.optLong("at")));
        if(entries.isEmpty()){empty("clock","No activity records yet","Observer registrations, policy changes, and hint results\nwill be recorded here.");return;}
        ui.add(body,ui.text("Recent integration, policy, and hint records. Each observer retains its latest 16 records.",13,MUTED),0);ui.space(body,16);LinearLayout list=ui.card();int count=0;
        for(Entry e:entries){if(count++>=80)break;String type=e.trace.getString("type");if(count>1){ui.divider(list,16);ui.space(list,16);}LinearLayout row=ui.row();row.setGravity(Gravity.TOP);row.addView(ui.icon(type.equals("accepted")?"check":type.equals("published")?"spark":"clock",type.equals("published")?ORANGE:MUTED,20));LinearLayout copy=ui.col();copy.addView(ui.bold(eventTitle(type),15,INK));ui.add(copy,ui.text(ProactivityWire.spec(shown(e.row).getJSONObject("spec")).name(),12,MUTED),7);ui.add(copy,ui.text(new SimpleDateFormat("MM-dd HH:mm",Locale.ROOT).format(new Date(e.trace.getLong("at"))),11,MUTED),7);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=ui.dp(13);row.addView(copy,p);row.setMinimumHeight(ui.dp(58));row.setBackground(ui.touch(Color.WHITE,10,0));row.setOnClickListener(v->showTrace(e));list.addView(row);}ui.add(body,list,0);
    }
    private String eventTitle(String type){return switch(type){case "registered"->"Integration or update request received";case "approved"->"Approved and enabled";case "disabled"->"Observer disabled";case "policy_edited"->"You edited the trigger policy";case "rolled_back"->"Previous policy restored";case "published"->"Hint displayed";case "accepted"->"You viewed the hint";case "expired"->"Hint expired";case "dismissed"->"Hint dismissed";case "gated"->"Device state is unsuitable for a hint";case "display_capacity"->"Hint display slots are full";default->"Hints temporarily unavailable";};}
    private void showTrace(Entry entry){JSONObject details=entry.trace.optJSONObject("details");String copy="";if(details!=null){JSONArray records=details.optJSONArray("records");copy=records==null?"This record comes from observer registration or policy maintenance.":"Selected "+records.length()+" events using policy #"+details.optLong("policyRevision")+".\nContext is fixed when the hint is shown and remains the same when viewed.";}new AlertDialog.Builder(this).setTitle(eventTitle(entry.trace.optString("type"))).setMessage(copy).setNegativeButton("Close",null).setPositiveButton("View observer",(d,w)->open(entry.key)).show();}
    private void empty(String icon,String title,String copy){LinearLayout area=ui.col();area.setGravity(Gravity.CENTER);area.setPadding(ui.dp(20),ui.dp(48),ui.dp(20),ui.dp(42));area.addView(ui.icon(icon,ORANGE,36));TextView heading=ui.bold(title,21,INK);heading.setGravity(Gravity.CENTER);ui.add(area,heading,22);TextView detail=ui.text(copy,14,MUTED);detail.setGravity(Gravity.CENTER);ui.add(area,detail,12);ui.add(body,area,0);}
    private void developerGuide(){new AlertDialog.Builder(this).setTitle("Developer integration").setMessage("1. Declare an observer and event type in an independent app.\n2. Register through the public Messenger protocol or optional SDK.\n3. Wait for user approval before reporting event metadata.\n\nProtocol: system/proactivity/API.md\nExample: examples/proactivity-observer\n\nPlugin developers maintain code, signatures, and releases. Hints currently trigger after page events settle.").setPositiveButton("Got it",null).show();}
}
