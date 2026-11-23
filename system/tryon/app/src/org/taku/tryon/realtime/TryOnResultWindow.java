// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.taku.tryon.common.IslandPublisher;

/** Terminal notification/card in the real SystemUI island, never an app overlay. */
final class TryOnResultWindow {
    static final String ACTION="org.taku.tryon.RESULT_ACTION";
    static final String TITLE="Try-on stopped";
    static final String DISCLOSURE="Camera is off. Retry starts a new cloud session, up to 60 seconds. Provider charges may apply.";
    private static final Uri HOST=Uri.parse("content://com.android.systemui.island");
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static TryOnResultWindow shown;
    final Context context;
    final String token=UUID.randomUUID().toString();
    final String detail;
    final long expires=SystemClock.elapsedRealtime()+120_000;
    private final String notificationId="tryon-result:"+token;
    private final IslandPublisher publisher;
    private final PendingIntent retry;
    private boolean closed,retryLaunched;
    private final Runnable expire=this::dismiss;
    private final Runnable expand=new Runnable(){
        int remaining=12;
        public void run(){
            if(closed||shown!=TryOnResultWindow.this||remaining--<=0)return;
            try{
                Bundle result=context.getContentResolver().call(HOST,"state",null,null);
                JSONObject state=new JSONObject(result==null?"{}":result.getString("json","{}"));
                JSONArray items=state.optJSONArray("activities");
                if(state.optBoolean("ok")&&items!=null)for(int i=0;i<items.length();i++){
                    JSONObject item=items.getJSONObject(i),payload=item.optJSONObject("payload");
                    JSONObject expanded=payload==null?null:payload.optJSONObject("expanded");
                    JSONObject content=expanded==null?null:expanded.optJSONObject("content");
                    if(context.getPackageName().equals(item.optString("package"))&&content!=null&&token.equals(content.optString("token"))){
                        // Expand this card only; never invoke its primary action or start a model.
                        Bundle answer=context.getContentResolver().call(HOST,"expand",item.getString("key"),null);
                        if(answer!=null&&new JSONObject(answer.getString("json","{}")).optBoolean("ok"))return;
                    }
                }
            }catch(Exception unavailable){/* Ordinary notification remains actionable. */}
            MAIN.postDelayed(this,150);
        }
    };
    private TryOnResultWindow(Context context,String detail){
        this.context=context.getApplicationContext();
        this.detail=detail==null||detail.isBlank()?"The preview could not continue. Please try again.":detail;
        publisher=new IslandPublisher(this.context,"tryon-progress","AI try-on");
        // Direct Activity PendingIntent avoids notification-to-BroadcastReceiver trampolines.
        retry=PendingIntent.getActivity(this.context,0,new Intent(this.context,TryOnRetryActivity.class).setAction("retry")
                .setData(Uri.parse("taku-tryon-result://"+token+"/retry")).putExtra("resultToken",token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void show(Context context,String detail){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->show(context,detail));return;}
        dismissCurrent();TryOnResultWindow card=new TryOnResultWindow(context,detail);shown=card;
        TryOnResultCard view=new TryOnResultCard(context,card.detail,()->{},()->{},()->{});
        int width=Math.min(view.dp(420),context.getResources().getDisplayMetrics().widthPixels-view.dp(16));
        PendingIntent open=PendingIntent.getActivity(context,412,new Intent(context,TryOnActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        boolean posted=card.publisher.postCard(card.notificationId,TITLE,card.detail+"\n\n"+DISCLOSURE,
                List.of(card.action("close","Close"),card.action("retry","Retry")),open,
                context.getPackageName()+"/org.taku.tryon.realtime.TryOnResultContentService",card.token,view.naturalHeightDp(width));
        if(posted){MAIN.post(card.expand);MAIN.postDelayed(card.expire,120_000);}else card.dismiss();
    }
    private IslandPublisher.Action action(String action,String label){
        if("retry".equals(action))return new IslandPublisher.Action(action,label,retry);
        Intent intent=new Intent(context,TryOnActionReceiver.class).setAction(ACTION)
                .setData(Uri.parse("taku-tryon-result://"+token+"/"+action)).putExtra("resultToken",token).putExtra("resultAction",action);
        return new IslandPublisher.Action(action,label,PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
    }
    static TryOnResultWindow current(String token){
        TryOnResultWindow card=shown;
        return card!=null&&!card.closed&&card.token.equals(token)&&SystemClock.elapsedRealtime()<card.expires?card:null;
    }
    static void act(Context context,String token,String action){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(()->act(context,token,action));return;}
        TryOnResultWindow card=current(token);
        if(card==null||!("close".equals(action)||"retry".equals(action)||"open".equals(action)))return;
        if("open".equals(action)){context.startActivity(new Intent(context,TryOnActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return;}
        if("close".equals(action)){card.dismiss();return;}
        if(card.retryLaunched)return;
        card.retryLaunched=true;
        try{card.retry.send();}catch(PendingIntent.CanceledException expired){card.dismiss();}
    }
    static boolean claimRetry(String token){
        TryOnResultWindow card=current(token);
        if(card==null)return false;
        card.dismiss();return true;
    }
    static void dismissCurrent(){
        if(Looper.myLooper()!=Looper.getMainLooper()){MAIN.post(TryOnResultWindow::dismissCurrent);return;}
        if(shown!=null)shown.dismiss();
    }
    private void dismiss(){
        if(closed)return;closed=true;MAIN.removeCallbacks(expire);MAIN.removeCallbacks(expand);publisher.dismiss(notificationId);if(shown==this)shown=null;
    }
}
