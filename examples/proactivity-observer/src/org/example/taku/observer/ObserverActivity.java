package org.example.taku.observer;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.text.*;
import android.widget.*;
import android.view.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.*;
import org.agentos.proactivity.api.*;

/** Community example: built only against android.jar and the exported SDK JAR. */
public final class ObserverActivity extends Activity implements ProactivityClient.Listener {
    private static final String ID="org.example.taku.observer.page",TYPE="org.example.taku.observer.page_changed";
    private ProactivityClient client;
    private TextView status,page;
    private LinearLayout diagnostics;
    private boolean connected;
    private int revision;
    private String duplicateId="";
    private boolean probed;
    private final JSONArray receipts=new JSONArray();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);revision=getPreferences(0).getInt("pageRevision",0);
        getWindow().getDecorView();getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        LinearLayout body=col();body.setPadding(dp(22),dp(22),dp(22),dp(28));ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xfff7f8f5);scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});scroll.requestApplyInsets();
        TextView tag=text("Developer example / v"+BuildVersion.VALUE,12,0xff6d736c);body.addView(tag);add(body,text("Page observer",30,0xff242825),12);add(body,text("Experience a proactive hint from real page changes.",14,0xff6d736c),9);
        LinearLayout connection=card();connection.addView(text("Observer connection",15,0xff242825));status=text("Connecting to Proactivity…",13,0xff6d736c);add(connection,status,9);
        add(connection,action("Review and authorize in Proactivity","open-manager",false,()->startActivity(new Intent().setComponent(new ComponentName(ProactivityWire.PACKAGE,ProactivityWire.PACKAGE+".ProactivityActivity")).putExtra("observerId",ID))),16);add(body,connection,24);
        LinearLayout demo=card();demo.addView(text("Try changing this page",18,0xff242825));add(demo,text("Type text or tap Update. After a pause, an approved observer offers a hint. Text stays on this page.",13,0xff6d736c),9);
        EditText field=new EditText(this);field.setTextSize(16);field.setTextColor(0xff242825);field.setHintTextColor(0xff6d736c);field.setHint("Write down a thought…");field.setContentDescription("Demo input field");field.setGravity(Gravity.TOP);field.setPadding(dp(14),dp(14),dp(14),dp(14));field.setBackground(shape(0xfff7f8f5,12));field.setMinHeight(dp(112));add(demo,field,18);
        page=text("Page revision "+revision,12,0xff6d736c);add(demo,page,12);
        field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){changed(false);}public void afterTextChanged(Editable e){}});
        add(demo,action("Update page","change-page",true,()->changed(false)),18);add(body,demo,16);
        TextView toggle=action("Developer diagnostics","diagnostics-toggle",false,()->diagnostics.setVisibility(diagnostics.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));add(body,toggle,18);
        diagnostics=col();diagnostics.setVisibility(View.GONE);add(diagnostics,text("Uses the public SDK with an independent signature and UID. Verify duplicate events and registration status.",12,0xff6d736c),12);
        add(diagnostics,action("Replay last event","duplicate-event",false,()->changed(true)),12);
        add(diagnostics,action("Inspect registration status","inspect-observer",false,()->{try{client.inspect(ID);}catch(Exception e){status.setText(e.getMessage());}}),10);add(body,diagnostics,0);
        client=new ProactivityClient(this,this);
    }
    @Override public void onStart(){super.onStart();try{client.connect();}catch(Exception e){status.setText(e.getMessage());}}
    @Override public void onStop(){client.close();connected=false;super.onStop();}
    @Override public void onConnected(){
        connected=true;
        try {
            if("event_without_register".equals(getIntent().getStringExtra("probe"))){client.emit(ID,"probe-"+UUID.randomUUID(),TYPE,revision);return;}
            client.register(new ObserverSpec(ID,"Page stability observer",TYPE,BuildVersion.VALUE==1?700:1200,
            1500,8,120000,"Page settled · Plugin v"+BuildVersion.VALUE,"The observer noticed a pause in page activity. Take a look?"));}
        catch(Exception e){status.setText(e.getMessage());}
    }
    private void changed(boolean duplicate) {
        if(!connected){status.setText("Not connected");return;}
        try {
            if(!duplicate || duplicateId.isEmpty()){duplicateId="event-"+UUID.randomUUID();revision++;getPreferences(0).edit().putInt("pageRevision",revision).apply();}
            client.emit(ID,duplicateId,TYPE,revision);page.setText("Page revision "+revision);
        }catch(Exception e){status.setText(e.getMessage());}
    }
    @Override public void onMessage(JSONObject receipt) {
        String type=receipt.optString("type");JSONObject result=receipt.optJSONObject("result");
        String copy=switch(type){
            case "register","inspect","policy_changed" -> result!=null&&result.optLong("pendingRevision")>0?"Waiting for you to approve this observer in Proactivity":result!=null&&result.optBoolean("enabled")?"Enabled · Page changes will be observed":"Disabled · Re-enable in Proactivity";
            case "published" -> "Hint displayed · Based on "+(result==null?0:result.optJSONArray("records").length())+" events";
            case "accepted" -> "You viewed the hint · This flow is complete";
            case "event" -> result!=null&&result.optBoolean("duplicate")?"Duplicate event deduplicated":"Page updated · Waiting for changes to settle";
            default -> "Received "+type+" receipt";
        };
        if(!receipt.optBoolean("ok"))copy=receipt.optString("code").equals("not_authorized")?"Not authorized yet. Review and approve in Proactivity first":"This operation did not complete: "+receipt.optString("message");status.setText(copy);
        receipts.put(receipt);while(receipts.length()>40)receipts.remove(0);
        try{Files.write(new File(getFilesDir(),"receipts.json").toPath(),new JSONObject().put("codeVersion",BuildVersion.VALUE).put("receipts",receipts).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        if(!probed && receipt.optBoolean("ok") && receipt.optString("type").equals("register")) {
            probed=true;String probe=getIntent().getStringExtra("probe");
            try {
                if("admin".equals(probe))client.request("approve",new JSONObject().put("observerId",ID));
                if("namespace".equals(probe))client.register(new ObserverSpec("another.app.rule","Foreign rule",TYPE,700,1500,8,120000,"Foreign","Foreign"));
                if("event_type".equals(probe))client.emit(ID,"probe-"+UUID.randomUUID(),"another.app.event",0);
            }catch(Exception error){status.setText(error.getMessage());}
        }
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);v.setLineSpacing(dp(3),1);if(size>=18)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return v;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(child,p);}
    private LinearLayout card(){LinearLayout v=col();v.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable bg=shape(Color.WHITE,20);bg.setStroke(dp(1),0xffe9ece5);v.setBackground(bg);return v;}
    private TextView action(String title,String key,boolean primary,Runnable click){TextView v=text(title,14,primary?Color.WHITE:0xff242825);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));v.setMinHeight(dp(48));v.setGravity(Gravity.CENTER);v.setPadding(dp(12),dp(14),dp(12),dp(14));v.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x186a705f),shape(primary?0xffff5a1f:0xfff7f8f5,14),null));v.setTag(key);v.setContentDescription(title);v.setFocusable(true);v.setOnClickListener(w->click.run());return v;}
    @Override public void onDisconnected(){connected=false;status.setText("Host disconnected. Reopen the page to reconnect");}
}
