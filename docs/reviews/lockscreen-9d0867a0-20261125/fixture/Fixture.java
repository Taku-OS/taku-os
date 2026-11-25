package org.taku.qa.lockscreen;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Icon;
import android.os.*;
import android.widget.*;
import android.view.*;
public class Fixture extends Activity {
 NotificationManager nm;
 Bitmap avatar(String letter,int color) {
  Bitmap b=Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(3);p.setColor(color);c.drawRoundRect(0,0,144,144,30,30,p);p.setColor(Color.WHITE);p.setTextSize(76);p.setTextAlign(Paint.Align.CENTER);c.drawText(letter,72,99,p);return b;
 }
 PendingIntent action(int id,String name){return PendingIntent.getActivity(this,id,new Intent(this,Fixture.class).putExtra("opened",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 Notification.Builder base(int id,String title,String text) {
  return new Notification.Builder(this,"qa").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setContentIntent(action(id,title)).setAutoCancel(true).setShowWhen(true).setVisibility(Notification.VISIBILITY_PUBLIC).setLargeIcon(avatar("Q",0xff47669c));
 }
 void publish(){
  nm.cancelAll();
  for(int i=8;i>=2;i--)nm.notify(i,base(i,i==2?"Leave in 20 mins":"QA update "+i,i==2?"Meeting at 9:45":"Test notification — swipe to dismiss").build());
  Person me=new Person.Builder().setName("You").build();Person sender=new Person.Builder().setName("Mary").setIcon(Icon.createWithBitmap(avatar("M",0xff936d9d))).build();
  Notification.MessagingStyle style=new Notification.MessagingStyle(me).setConversationTitle("#Product").setGroupConversation(true).addMessage("The Feed scope is still open for today's review. We need to decide which parts belong in this prototype.",System.currentTimeMillis(),sender);
  nm.notify(1,base(1,"Mary","Design review").setStyle(style).addAction(new Notification.Action.Builder(null,"Add to tasks",action(101,"Add to tasks action")).build()).addAction(new Notification.Action.Builder(null,"Open chat",action(102,"Open chat action")).build()).build());
 }
 @Override public void onCreate(Bundle b){super.onCreate(b);nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("qa","Acceptance fixtures",NotificationManager.IMPORTANCE_DEFAULT));
  String command=getIntent().getStringExtra("command");
  if("publish".equals(command))publish();if("clear".equals(command))nm.cancelAll();
  try {if("wallpaper".equals(command))getSystemService(WallpaperManager.class).setStream(getAssets().open("lock.jpg"),null,true,WallpaperManager.FLAG_LOCK);
   if("clear-wallpaper".equals(command))getSystemService(WallpaperManager.class).clear(WallpaperManager.FLAG_LOCK);
  }catch(Exception e){throw new RuntimeException(e);}
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(48,96,48,48);root.setBackgroundColor(0xffe9eef5);setContentView(root);
  TextView title=new TextView(this);title.setText("Lockscreen QA");title.setTextSize(30);title.setTextColor(0xff243750);root.addView(title);
  TextView detail=new TextView(this);detail.setText(getIntent().hasExtra("opened")?"Notification destination\n\n"+getIntent().getStringExtra("opened"):"Acceptance test app\n\nThese notifications are test fixtures, not real messages.");detail.setTextSize(21);detail.setPadding(0,50,0,50);root.addView(detail);
  Button post=new Button(this);post.setText("Publish test notifications");post.setOnClickListener(v->publish());root.addView(post);
  Button clear=new Button(this);clear.setText("Clear test notifications");clear.setOnClickListener(v->nm.cancelAll());root.addView(clear);
 }
}
