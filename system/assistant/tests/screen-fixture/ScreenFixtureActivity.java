package org.agentos.screenfixture;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
/** Synthetic integration data, never presented as a real messaging app. */
public final class ScreenFixtureActivity extends Activity {
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(28,70,28,30);page.setBackgroundColor(Color.rgb(248,247,244));
  TextView title=new TextView(this);title.setText("合成约饭 · 后台交互验收");title.setTextSize(21);page.addView(title);
  TextView note=new TextView(this);note.setText("Synthetic test page · not a live chat");note.setTextSize(12);page.addView(note);
  for(String line:new String[]{"刘：明天晚上我们仨一起吃个饭？","L：好呀，想吃菌汤火锅。","L：最好清淡一点，不要辣。","刘：可以，餐厅和时间还没确定。"}) {
   TextView text=new TextView(this);text.setText(line);text.setTextSize(20);text.setTextColor(Color.rgb(35,35,35));text.setPadding(15,28,15,28);page.addView(text);
  }
  setContentView(page);
 }
}
