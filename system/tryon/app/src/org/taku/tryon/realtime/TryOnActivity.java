// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import org.taku.tryon.ui.Ui;
import org.taku.cosplay.api.CosplayContract;

/** Visible camera start boundary. Picking an image or changing settings never contacts a model. */
public class TryOnActivity extends Activity {
    private Ui ui;
    private TextView status,reference;
    private EditText prompt,key;
    private Spinner mode,provider,region;
    private Switch voiceEnabled,voicePreview;
    private TextView voiceStatus;
    private boolean renderingVoice;
    private String storedConfiguration="";
    private Uri selected;
    private long referenceExpires,draftRevision;
    private boolean resumeAction,loadingDraft;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){render();main.postDelayed(this,500);}};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);ui=new Ui(this);
        ScrollView scroll=new ScrollView(this);LinearLayout column=ui.column();column.setPadding(ui.dp(24),ui.dp(32),ui.dp(24),ui.dp(24));scroll.addView(column);setContentView(scroll);
        ui.add(column,ui.text("AI try-on",28,Ui.INK,true));ui.space(column,12);
        ui.add(column,ui.text("Uses your front camera and your saved model key. Each explicit start or retry opens one cloud session, up to 60 seconds. Provider charges may apply. Stop closes the camera and cloud connection.",15,Ui.MUTED,false));ui.space(column,16);
        mode=spinner(column,"Mode",new String[]{"Outfit","Character"});
        provider=spinner(column,"Model provider",new String[]{"Lucy","XMax"});region=spinner(column,"XMax region",new String[]{"Global","China"});
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int n,long id){loadConfiguration();saveDraft();}public void onNothingSelected(android.widget.AdapterView<?> p){}});
        button(column,"Save model settings",()->show(TryOnController.configure(this,configurationArgs())));
        key=new EditText(this);key.setHint("Provider key");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSingleLine(true);ui.add(column,key);
        button(column,"Save encrypted key",()->{show(TryOnController.credential(this,providerId(),key.getText().toString(),false));key.setText("");});
        button(column,"Remove key",()->show(TryOnController.credential(this,providerId(),null,true)));
        reference=ui.text("Select a reference image",15,Ui.INK,false);ui.add(column,reference);
        button(column,"Choose reference image",()->{if(CallTryOnService.busy()){show(TryOnController.result(false,"busy"));return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),31);});
        prompt=new EditText(this);prompt.setHint("Optional outfit or character description");prompt.setMaxLines(4);ui.add(column,prompt);
        prompt.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){saveDraft();}public void afterTextChanged(android.text.Editable value){}});
        button(column,"Allow camera",()->requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.POST_NOTIFICATIONS},32));
        button(column,"Allow preview over other apps",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        button(column,"Start 60-second preview",this::start);
        button(column,"Retry previous preview",()->show(TryOnController.retry(this)));
        button(column,"Minimize",()->show(TryOnController.presentation("minimize")));
        button(column,"Expand",()->show(TryOnController.presentation("expand")));
        button(column,"Full screen",()->show(TryOnController.presentation("fullscreen")));
        button(column,"Stop",()->show(TryOnController.stop(this)));
        status=ui.text("",14,Ui.INK,false);ui.space(column,16);ui.add(column,status);
        ui.space(column,24);ui.add(column,ui.text("TikTok character requests",20,Ui.INK,true));
        ui.add(column,ui.text("Enable the Assistant entry for the TikTok video you are watching. Voice-only keeps the camera off; preview starts only after you tap Try on.",14,Ui.MUTED,false));
        voiceEnabled=new Switch(this);voiceEnabled.setText("Enable voice character requests");ui.add(column,voiceEnabled);
        voicePreview=new Switch(this);voicePreview.setText("Allow camera preview after Try on");ui.add(column,voicePreview);
        voiceEnabled.setOnCheckedChangeListener((button,enabled)->saveVoiceSetting("enabled",enabled));
        voicePreview.setOnCheckedChangeListener((button,enabled)->saveVoiceSetting("voiceOnly",!enabled));
        voiceStatus=ui.text("",14,Ui.MUTED,false);ui.add(column,voiceStatus);
        button(column,"Assistant voice language",()->{
            try{startActivity(new Intent().setComponent(new ComponentName(CosplayContract.SPEECH_SETTINGS_PACKAGE,CosplayContract.SPEECH_SETTINGS_ACTIVITY)));}
            catch(ActivityNotFoundException unavailable){Toast.makeText(this,"Install Taku Audio to open Assistant voice settings.",Toast.LENGTH_LONG).show();}
        });
        loadDraft();loadConfiguration();renderVoiceSettings();handle(getIntent());
    }
    private void saveVoiceSetting(String key,boolean value){
        if(renderingVoice)return;
        Bundle values=new Bundle();values.putString("schema",CosplayContract.SCHEMA);values.putBoolean(key,value);
        Bundle result=LegacyCosplayGateway.call(this,CosplayContract.CONFIGURE,null,values,null);
        Toast.makeText(this,result.getString("userMessage","Could not save voice settings."),Toast.LENGTH_SHORT).show();renderVoiceSettings();
    }
    private void renderVoiceSettings(){
        if(voiceStatus==null)return;
        Bundle value=LegacyCosplayGateway.call(this,CosplayContract.MODEL_STATUS,null,null,null);
        renderingVoice=true;
        voiceEnabled.setChecked(value.getBoolean("enabled"));voicePreview.setChecked(!value.getBoolean("voiceOnly",true));
        voiceEnabled.setEnabled(!CallTryOnService.busy());voicePreview.setEnabled(!CallTryOnService.busy());
        voiceStatus.setText(value.getString("userMessage","Voice settings are unavailable."));renderingVoice=false;
    }
    private Spinner spinner(LinearLayout column,String label,String[] values){ui.add(column,ui.text(label,14,Ui.MUTED,false));Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));ui.add(column,s);return s;}
    private void button(LinearLayout column,String label,Runnable action){Button b=ui.button(label,false);b.setOnClickListener(v->action.run());ui.space(column,8);ui.add(column,b);}
    private String modeId(){return mode.getSelectedItemPosition()==1?TryOnController.CHARACTER:TryOnController.OUTFIT;}
    private String providerId(){return provider.getSelectedItemPosition()==1?"xmax":"lucy";}
    private Bundle configurationArgs(){Bundle b=new Bundle();b.putString("mode",modeId());b.putString("provider",providerId());b.putString("region",region.getSelectedItemPosition()==1?"china":"global");return b;}
    private void loadConfiguration(){if(provider==null)return;RealtimeCallConfiguration c=TryOnController.configuration(this,modeId());storedConfiguration=modeId()+":"+c.providerId()+":"+c.environment;provider.setSelection(c.provider==RealtimeCallConfiguration.Provider.XMAX?1:0);region.setSelection("china".equals(c.environment)?1:0);}
    private void saveDraft(){
        if(loadingDraft||prompt==null)return;
        Bundle b=new Bundle();b.putString("mode",modeId());b.putString("referenceUri",selected==null?"":selected.toString());b.putString("prompt",prompt.getText().toString());b.putLong("referenceExpires",referenceExpires);
        Bundle saved=TryOnController.draft(this,b);draftRevision=saved.getLong("revision",draftRevision);
    }
    private void loadDraft(){
        Bundle b=TryOnController.draft(this,null);draftRevision=b.getLong("revision");loadingDraft=true;
        mode.setSelection(TryOnController.CHARACTER.equals(b.getString("mode"))?1:0);
        String uri=b.getString("referenceUri","");selected=uri.isEmpty()?null:Uri.parse(uri);referenceExpires=b.getLong("referenceExpires",0);
        prompt.setText(b.getString("prompt",""));reference.setText(selected==null?"Select a reference image":"Reference selected: "+selected);loadingDraft=false;
    }
    private void start(){
        if(selected==null){show(TryOnController.result(false,"reference_required"));return;}
        Bundle args=new Bundle();args.putString("mode",modeId());args.putString("referenceUri",selected.toString());args.putString("prompt",prompt.getText().toString());args.putLong("referenceExpires",referenceExpires);
        Bundle result=TryOnController.start(this,args);show(result);
    }
    private void show(Bundle b){Toast.makeText(this,b.getString("code","unknown"),Toast.LENGTH_SHORT).show();render();}
    private void render(){
        if(status==null)return;
        renderVoiceSettings();
        boolean editable=!CallTryOnService.busy();mode.setEnabled(editable);provider.setEnabled(editable);region.setEnabled(editable);prompt.setEnabled(editable);key.setEnabled(editable);
        Bundle draft=TryOnController.draft(this,null);if(draft.getLong("revision")!=draftRevision)loadDraft();
        RealtimeCallConfiguration config=TryOnController.configuration(this,modeId());
        if(!storedConfiguration.equals(modeId()+":"+config.providerId()+":"+config.environment))loadConfiguration();
        try {
            org.json.JSONObject s=new org.json.JSONObject(TryOnController.status(this).getString("json","{}"));
            status.setText(s.optString("detail")+"\n"+(s.optBoolean("active")?s.optLong("remainingSeconds")+" seconds remaining\n":"")
                    +"Lucy key: "+(s.optBoolean("lucyKeyConfigured")?"configured":"not configured")+"\nXMax key: "+(s.optBoolean("xmaxKeyConfigured")?"configured":"not configured")
                    +(s.optBoolean("cleanupPending")?"\nFinishing the previous connection. Wait before starting again.":""));
        }catch(Exception ignored){status.setText("State unavailable");}
    }
    @Override protected void onResume(){super.onResume();TryOnController.visible(true);loadDraft();main.post(refresh);if(resumeAction){resumeAction=false;handleAction(getIntent());}}
    @Override protected void onPause(){TryOnController.visible(false);main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent){
        resumeAction=true;
        if(hasWindowFocus()){resumeAction=false;handleAction(intent);}
    }
    private void handleAction(Intent intent){
        if(!(this instanceof TryOnRetryActivity))return;
        if(this instanceof TryOnRetryActivity&&"retry".equals(intent.getAction())){show(TryOnController.retry(this));return;}
        if("expand".equals(intent.getAction())&&CallTryOnService.busy()){TryOnController.presentation("expand");finish();}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==31&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){selected=data.getData();referenceExpires=0;
            try{getContentResolver().takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            reference.setText("Reference selected: "+selected);saveDraft();}
    }
}
