package org.agentos.memorytests;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;

/** Labelled synthetic screen only; has no permission to write the production memory database. */
public final class PageActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);show();}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);show();}
 void show(){String mode=getIntent().getStringExtra("mode");if(mode==null)mode="normal";String heading=getIntent().getStringExtra("title");if(heading==null)heading="合成页面 · 自动记忆验收";setTitle(heading);
  if(mode.equals("secure"))getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
  LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(35,85,35,30);
  TextView title=new TextView(this);title.setText(heading);title.setTextSize(24);l.addView(title);
  TextView text=new TextView(this);text.setText(getIntent().getStringExtra("body")==null?"合成聊天：周六 18:00 在杭州西湖附近聚餐，4 人，人均 150 元。餐厅与集合地点尚未确定，请先整理事实、备选方向与待确认项。这不是用户真实聊天。":getIntent().getStringExtra("body"));text.setTextSize(22);l.addView(text);
  EditText input=new EditText(this);input.setHint("未发送输入，不应记忆");input.setText("UNSENT_DRAFT_MUST_NOT_BE_RECORDED");if(mode.equals("password"))input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);l.addView(input);
  l.setFocusableInTouchMode(true);l.requestFocus();setContentView(l);
 }
}
