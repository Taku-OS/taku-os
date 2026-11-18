package org.agentos.assistant;
import android.content.Context;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Inline native questions and approvals. Retains partially typed answers only in memory. */
final class NativeRequestView extends LinearLayout {
 static final Map<String,Map<String,String>> drafts=new HashMap<>();
 static boolean editing;
 final JSONObject request;final AgentSettingsUi ui;final TextView status;final Map<String,EditText> inputs=new LinkedHashMap<>();
 NativeRequestView(Context context,DrawerTheme theme,JSONObject request){super(context);setOrientation(VERTICAL);this.request=request;ui=new AgentSettingsUi(context,theme);setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14));setBackground(theme.rounded(context,theme.raised,18,true));status=ui.text("",12,false);
  try{String method=request.getString("method");JSONObject p=request.getJSONObject("params");
   if(!BackgroundQuestion.supports(method))ui.add(this,ui.text(method.equals("item/tool/requestUserInput")?"More information needed":"Waiting for your choice",17,true),0);
   if(BackgroundQuestion.supports(method)){
    android.os.LocaleList locales=context.getResources().getConfiguration().getLocales();
    BackgroundQuestion question=BackgroundQuestion.fromPending(method,p,locales.isEmpty()?Locale.getDefault():locales.get(0));
    org.agentos.surfaces.SurfaceSpec surface=question.surface("inline-phone-question",1);
    ui.add(this,ui.text(surface.title(),17,true),0);ui.add(this,ui.text(surface.summary(),14,false),8);
    for(org.agentos.surfaces.SurfaceSpec.Block block:surface.blocks()){
     ui.add(this,ui.text(block.label(),12,true),14);ui.add(this,ui.text(block.text(),15,block.type().equals("notice")),4);
    }
    for(org.agentos.surfaces.SurfaceSpec.Action action:surface.actions())ui.add(this,ui.button(action.label(),action.primary(),()->{
     try{JSONObject response=question.response(action.id(),1,1);if(response!=null)reply(response);}catch(Exception error){status.setText(error.getMessage());}
    }),10);
   }else if(method.equals("item/tool/requestUserInput")){
    JSONArray questions=p.getJSONArray("questions");Map<String,String> memory=drafts.computeIfAbsent(request.getString("key"),k->new HashMap<>());
    for(int i=0;i<questions.length();i++){
     JSONObject q=questions.getJSONObject(i);String id=q.getString("id");ui.add(this,ui.text(q.optString("question"),15,true),12);
     EditText input=ui.input("Enter or select answers",q.optBoolean("isSecret")?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);input.setText(memory.getOrDefault(id,""));input.setOnFocusChangeListener((v,focused)->{if(focused)editing=true;});input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){memory.put(id,s.toString());}public void afterTextChanged(android.text.Editable e){}});inputs.put(id,input);
     JSONArray options=q.optJSONArray("options");if(options!=null)for(int j=0;j<options.length();j++){JSONObject option=options.getJSONObject(j);String label=option.getString("label");ui.add(this,ui.button(label,false,()->input.setText(label)),6);if(!option.optString("description").isEmpty())ui.add(this,ui.text(option.getString("description"),12,false),2);}
     ui.add(this,input,8);
    }
    ui.add(this,ui.button("Submit answer",true,()->{try{JSONObject answers=new JSONObject();for(Map.Entry<String,EditText> e:inputs.entrySet()){String text=e.getValue().getText().toString().trim();if(text.isEmpty()){status.setText("Answer the questions above");return;}answers.put(e.getKey(),new JSONObject().put("answers",new JSONArray().put(text)));}reply(new JSONObject().put("answers",answers));}catch(Exception e){status.setText("Invalid answer format");}}),12);
   }else if(method.equals("account/chatgptAuthTokens/refresh")||method.equals("attestation/generate")){
    ui.add(this,ui.text(method.equals("attestation/generate")?"This service requires valid device attestation. You can submit an existing attestation token; this ROM does not yet implement automatic issuance.":"This conversation uses externally managed account authorization. Submit currently valid authorization for that account.",14,false),10);
    String[] keys=method.equals("attestation/generate")?new String[]{"token"}:new String[]{"accessToken","chatgptAccountId"};
    for(String key:keys){EditText input=ui.input(key,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);inputs.put(key,input);ui.add(this,input,8);}
    ui.add(this,ui.button("Submit authorization",true,()->{try{JSONObject response=new JSONObject();for(Map.Entry<String,EditText> e:inputs.entrySet()){if(e.getValue().getText().length()==0){status.setText("Fill in the authorization fields");return;}response.put(e.getKey(),e.getValue().getText().toString());}reply(response);for(EditText input:inputs.values())input.setText("");}catch(Exception ignored){}}),12);
   }else if(method.equals("item/tool/call")){
    ui.add(this,ui.text("Extension tool: "+p.optString("tool"),15,true),10);EditText output=ui.input("Return tool execution result",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);ui.add(this,output,8);ui.add(this,ui.button("Return result",true,()->{try{reply(new JSONObject().put("success",true).put("contentItems",new JSONArray().put(new JSONObject().put("type","inputText").put("text",output.getText().toString()))));}catch(Exception ignored){}}),10);
   }else if(method.equals("mcpServer/elicitation/request")){
    ui.add(this,ui.text(p.optString("message"),15,false),10);
    if(p.optString("mode").equals("url")){ui.add(this,ui.button("Open authorization page",true,()->{String url=p.optString("url");if(url.startsWith("https://"))context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}),8);}
    JSONObject schema=p.optJSONObject("requestedSchema");JSONObject props=schema==null?null:schema.optJSONObject("properties");if(props!=null)for(Iterator<String> it=props.keys();it.hasNext();){String key=it.next();ui.add(this,ui.text(props.getJSONObject(key).optString("title",key),14,true),8);EditText input=ui.input(key,InputType.TYPE_CLASS_TEXT);inputs.put(key,input);ui.add(this,input,4);}
    ui.add(this,ui.button("Submit",true,()->{try{JSONObject content=new JSONObject();for(Map.Entry<String,EditText> e:inputs.entrySet()){String value=e.getValue().getText().toString();JSONObject property=props.optJSONObject(e.getKey());String type=property==null?"string":property.optString("type");Object typed=type.equals("boolean")?Boolean.parseBoolean(value):type.equals("integer")?Long.parseLong(value):type.equals("number")?Double.parseDouble(value):value;content.put(e.getKey(),typed);}reply(new JSONObject().put("action","accept").put("content",content));}catch(Exception ignored){}}),12);
   }else{
    ui.add(this,ui.text(p.optString("reason",p.optString("command","This operation requires a choice.")),14,false),10);
    ui.add(this,ui.button("Allow this time",true,()->{try{String decision=method.equals("applyPatchApproval")||method.equals("execCommandApproval")?"approved":"accept";JSONObject response=new JSONObject().put("decision",decision);if(method.equals("item/permissions/requestApproval"))response=new JSONObject().put("permissions",p.optJSONObject("permissions")).put("scope","turn");reply(response);}catch(Exception ignored){}}),12);
   }
   if(!BackgroundQuestion.supports(method))ui.add(this,ui.button("Cancel",false,()->{try{if(method.equals("attestation/generate")||method.equals("account/chatgptAuthTokens/refresh")){NativeClient.async(new JSONObject().put("op","reject").put("key",request.getString("key")),new NativeClient.Callback(){public void ready(JSONObject r){editing=false;status.setText("Cancelled");}public void failed(String m){status.setText(m);}});return;}JSONObject response=method.equals("item/tool/requestUserInput")?new JSONObject().put("answers",new JSONObject()):method.equals("mcpServer/elicitation/request")?new JSONObject().put("action","cancel").put("content",JSONObject.NULL):method.equals("item/permissions/requestApproval")?new JSONObject().put("permissions",new JSONObject()).put("scope","turn"):method.equals("item/tool/call")?new JSONObject().put("success",false).put("contentItems",new JSONArray()):new JSONObject().put("decision",method.equals("applyPatchApproval")||method.equals("execCommandApproval")?"abort":"cancel");reply(response);}catch(Exception ignored){}}),8);ui.add(this,status,6);
  }catch(Exception e){ui.add(this,ui.text("Native request could not be displayed. Reopen the assistant.",14,false),0);}
 }
 void reply(JSONObject response){
  status.setText("Submitting…");
  NativeClient.respond(request.optString("key"),response,new NativeClient.Callback(){
   public void ready(JSONObject result){
    status.setText("Submitted");
    editing=false;drafts.remove(request.optString("key"));
    android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if(ime!=null)ime.hideSoftInputFromWindow(getWindowToken(),0);
   }
   public void failed(String message){status.setText(message);}
   });
 }
}
