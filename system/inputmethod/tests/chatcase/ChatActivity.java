package org.agentos.inputmethod.chatcase;
import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Synthetic source UI only. Contains no model answer or reply-bar implementation. */
public final class ChatActivity extends Activity {
 int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
 GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);
  if(getIntent().getBooleanExtra("secure",false))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  getWindow().getDecorView();getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xfff5f3ee);root.setFocusableInTouchMode(true);
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());int keyboard=insets.getInsets(WindowInsets.Type.ime()).bottom;v.setPadding(0,bars.top,0,Math.max(bars.bottom,keyboard));return insets;});
  LinearLayout header=new LinearLayout(this);header.setOrientation(0);header.setPadding(dp(18),dp(14),dp(18),dp(14));header.setGravity(Gravity.CENTER_VERTICAL);header.setBackgroundColor(0xfffbfaf7);
  TextView back=text("‹",30,0xff20211e);header.addView(back,new LinearLayout.LayoutParams(dp(36),dp(48)));
  LinearLayout labels=new LinearLayout(this);labels.setOrientation(1);TextView title=text("周五约饭局",21,0xff20211e);title.setTypeface(null,Typeface.BOLD);labels.addView(title);labels.addView(text("4 人 · 本地群聊示例",12,0xff8a8983));header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));header.addView(text("···",22,0xff55554e));root.addView(header);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout messages=new LinearLayout(this);messages.setOrientation(1);messages.setPadding(dp(18),dp(12),dp(18),dp(20));scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  TextView time=text("今天 18:42",12,0xff9a9890);time.setGravity(Gravity.CENTER);messages.addView(time,new LinearLayout.LayoutParams(-1,dp(32)));
  bubble(messages,"阿泽","泽",0xffdfb97f,"周五下班一起吃个饭吧？");
  bubble(messages,"小林","林",0xffa7bfa6,"我七点以后都可以，想吃火锅！");
  bubble(messages,"米粒","米",0xffc6b5d9,"地铁口那家怎么样？走过去很近。");
  bubble(messages,"阿泽","泽",0xffdfb97f,"那就周五七点半，地铁口那家火锅。@我 你能来吗？");
  LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);composer.setPadding(dp(14),dp(10),dp(14),dp(10));composer.setBackgroundColor(0xfffbfaf7);
  composer.addView(text("＋",26,0xff77766f),new LinearLayout.LayoutParams(dp(36),dp(46)));
  EditText editor=new EditText(this);editor.setSingleLine(false);editor.setMaxLines(3);editor.setTextSize(17);editor.setTextColor(0xff24241f);editor.setHint("说点什么…");editor.setHintTextColor(0xffaaa79d);editor.setBackground(bg(0xffffffff,12));editor.setPadding(dp(12),dp(10),dp(12),dp(10));editor.setContentDescription("群聊输入框");editor.setCursorVisible(!getIntent().getBooleanExtra("stableCursor",false));editor.setInputType(getIntent().getBooleanExtra("password",false)?129:131073);composer.addView(editor,new LinearLayout.LayoutParams(0,-2,1));
  TextView smile=text("☺",27,0xff77766f);smile.setPadding(dp(10),0,0,0);composer.addView(smile,new LinearLayout.LayoutParams(dp(40),dp(46)));root.addView(composer);setContentView(root);root.requestFocus();
  editor.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){try(FileOutputStream out=openFileOutput("state.json",MODE_PRIVATE)){out.write(new JSONObject().put("text",s.toString()).toString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}}public void afterTextChanged(Editable e){}});
  root.getViewTreeObserver().addOnGlobalLayoutListener(()->scroll.post(()->scroll.scrollTo(0,messages.getHeight())));
 }
 void bubble(LinearLayout parent,String name,String initial,int color,String body){
  LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(9),0,dp(9));row.setGravity(Gravity.TOP);
  TextView avatar=text(initial,18,Color.WHITE);avatar.setGravity(Gravity.CENTER);avatar.setTypeface(null,Typeface.BOLD);avatar.setBackground(bg(color,12));row.addView(avatar,new LinearLayout.LayoutParams(dp(40),dp(40)));
  LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setPadding(dp(10),0,dp(18),0);column.addView(text(name,12,0xff89867e));
  TextView message=text(body,16,0xff272823);message.setPadding(dp(14),dp(11),dp(14),dp(11));message.setBackground(bg(Color.WHITE,14));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.topMargin=dp(5);column.addView(message,params);row.addView(column,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row);
 }
}
