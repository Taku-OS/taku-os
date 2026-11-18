package org.agentos.surfacedemo;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.ScrollView;
import org.agentos.surfaces.client.SurfaceClient;
import org.agentos.surfaces.SurfaceSpec;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic fixtures only. Not linked or packaged into the system renderer. */
public final class PreviewActivity extends Activity implements SurfaceClient.Listener {
    private SurfaceClient client; private TextView events; private long revision; private boolean ready,bottom;
    private String pending,shownKind="choice";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout content=new LinearLayout(this); content.setOrientation(1);
        content.setPadding(24,100,24,32); content.setBackgroundColor(0xffe9eee7);
        TextView title=new TextView(this); title.setText(local("Taku 系统展示 · 独立预览","Taku surfaces · preview")); title.setTextSize(24); content.addView(title);
        TextView explanation=new TextView(this); explanation.setText(local("以下内容均为合成测试，不会创建真实预订。这个页面是普通调用方，展示由另一个系统应用承载。","Synthetic layout tests only. No real reservation will be made. This caller uses the separate native surface host.")); content.addView(explanation);
        add(content,local("提示示例","Hint"),() -> publish("hint")); add(content,local("选择示例","Choice"),() -> publish("choice"));
        add(content,local("结果示例","Result"),() -> publish("result"));
        add(content,local("结构化确认 · 合成测试","Structured confirmation · synthetic"),() -> publish("confirmation"));
        add(content,local("切换顶部 / 底部","Switch top / bottom"),() -> { bottom=!bottom; publish(shownKind); });
        add(content,local("移除展示","Dismiss surface"),() -> { try { client.dismiss("preview"); } catch (Exception e) { events.setText(e.toString()); } });
        EditText input=new EditText(this); input.setHint(local("在这里测试键盘与中文输入","Test keyboard input here")); input.setTextColor(Color.BLACK); content.addView(input);
        events=new TextView(this); events.setText(local("正在连接系统展示服务…","Connecting to surface host…")); content.addView(events);
        ScrollView viewport=new ScrollView(this); viewport.addView(content); setContentView(viewport);
        pending=getIntent().getStringExtra("scenario"); bottom=getIntent().getBooleanExtra("bottom",false);
        client=new SurfaceClient(this,this); client.connect();
    }
    private void add(LinearLayout parent,String label,Runnable action) {
        Button button=new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); parent.addView(button);
    }
    @Override public void onConnected() { ready=true; events.setText(local("系统服务已连接","Surface host connected")); if (pending!=null) publish(pending); }
    @Override public void onEvent(String type,String id,long version,String value) {
        String text=type+" · "+id+" · r"+version+" · "+value;
        events.setText(text); android.util.Log.i("SurfaceDemo",text);
    }
    @Override public void onAction(SurfaceClient.ActionEvent event) {
        String artifact=event.artifact()==null?"none":event.artifact().ref()+" | "+event.artifact().mediaType()+" | "+event.artifact().title();
        String text="typed-action | "+event.id()+" | "+event.actionId()+" | "+artifact;
        events.setText(text); android.util.Log.i("SurfaceDemo",text);
    }
    private void publish(String kind) {
        if (!ready) return;
        shownKind=kind;
        try {
            long version=System.currentTimeMillis(); revision=Math.max(revision+1,version);
            if (!kind.equals("legacy") && !kind.equals("invalid")) {
                List<SurfaceSpec.Block> blocks=List.of(new SurfaceSpec.Block("field","Event","Weekend meetup"),new SurfaceSpec.Block("field","Location","West Lake · Lakeside"));
                SurfaceSpec spec=switch (kind) {
                    case "confirmation" -> confirmation();
                    case "confirmation-fields" -> confirmationFields();
                    case "hint" -> SurfaceSpec.hint("preview",revision,"Review your weekend plans?","Some weekend ideas are ready to explore.",new SurfaceSpec.Action("accept","View plans",true));
                    case "result" -> SurfaceSpec.result("preview",revision,"Weekend resources are ready","Your organized resources are here.",new SurfaceSpec.ArtifactRef("demo:weekend-guide","text/markdown","Weekend ideas.md"));
                    default -> SurfaceSpec.choice("preview",revision,"Choose a suitable time","Choose a time, then return to what you were doing.",List.of(new SurfaceSpec.Action("option-a","Saturday 18:30",true),new SurfaceSpec.Action("option-b","Sunday 19:00",false))).withBlocks(blocks);
                };
                spec=spec.withPlacement(bottom?"bottom":"top").withTtlMs(getIntent().getLongExtra("ttlMs",300000));
                if (getIntent().getBooleanExtra("compact",false)) spec=spec.withInitialState("compact");
                client.publish(spec);
                return;
            }
            boolean choice=kind.equals("legacy"), result=false;
            JSONObject value=new JSONObject().put("version",1).put("id","preview").put("revision",revision)
                    .put("kind",kind.equals("legacy")?"choice":kind).put("placement",bottom?"bottom":"top").put("ttlMs",getIntent().getLongExtra("ttlMs",300000))
                    .put("title",choice?"Choose a suitable time":result?"Ready whenever you are":"Something new is ready. Take a look?")
                    .put("summary",choice?"Choose a time, then return to what you were doing.":result?"The content and highlights are organized together.":"A new collection of weekend ideas is ready.")
                    .put("blocks",new JSONArray()
                        .put(new JSONObject().put("type","field").put("label",choice?"Event":"Content").put("text",choice?"Weekend meetup":"Weekend ideas collection"))
                        .put(new JSONObject().put("type","field").put("label",choice?"Location":"Items").put("text",choice?"West Lake · Lakeside":"3 items")))
                    .put("actions",new JSONArray().put(new JSONObject().put("id",choice?"option-a":result?"details":"open")
                        .put("label",choice?"Saturday 18:30":result?"View details":"Open").put("primary",true)));
            if (choice) value.getJSONArray("actions").put(new JSONObject().put("id","option-b").put("label","Sunday 19:00").put("primary",false));
            client.publish(value.toString());
        } catch (Exception error) { events.setText(error.toString()); }
    }
    private String local(String zh,String en) { return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en; }
    private SurfaceSpec confirmation() {
        return SurfaceSpec.choice("preview",revision,local("订座确认 · 布局预览","Reservation · layout preview"),
                local("海底捞火锅（龙阳广场店）\n上海 · 浦东 · 龙阳广场","Haidilao Hot Pot · Longyang Plaza\nPudong, Shanghai"),List.of(
                    new SurfaceSpec.Action("confirm",local("确认并订座","Confirm reservation"),true),
                    new SurfaceSpec.Action("modify",local("修改信息","Modify details"),false),
                    new SurfaceSpec.Action("cancel",local("暂不订座","Cancel"),false)))
                .withBlocks(List.of(new SurfaceSpec.Block("fact",local("日期","Date"),local("2026年10月26日","Oct 26, 2026")),
                    new SurfaceSpec.Block("fact",local("时间","Time"),"20:00"),
                    new SurfaceSpec.Block("fact",local("人数","Guests"),local("3 人","3 people")),
                    new SurfaceSpec.Block("notice",local("过敏备注","Allergy note"),local("1 人海鲜过敏。请由门店确认食材及交叉接触风险。","1 guest has a seafood allergy. Ask the restaurant to confirm ingredients and cross-contact risks.")),
                    new SurfaceSpec.Block("text","",local("合成布局测试，不会提交真实预订。","Synthetic layout test. No real reservation will be submitted."))));
    }
    private SurfaceSpec confirmationFields() {
        SurfaceSpec preview=confirmation();
        return preview.withBlocks(List.of(
                new SurfaceSpec.Block("field",local("门店","Restaurant"),local("海底捞火锅（龙阳广场店）｜上海市浦东新区龙阳路2000号龙阳广场4楼","Haidilao Hot Pot · Longyang Plaza | Level 4, 2000 Longyang Road, Pudong, Shanghai")),
                new SurfaceSpec.Block("field",local("日期时间","Date and time"),local("2026年10月26日 20:00（上海时区）","October 26, 2026 at 20:00 (Shanghai time)")),
                new SurfaceSpec.Block("field",local("人数及区域","Guests and area"),local("3 人，大厅","3 people, main dining room")),
                new SurfaceSpec.Block("field",local("用餐人","Contact"),local("使用已保存的联系人和手机号","Use the saved contact name and phone number")),
                new SurfaceSpec.Block("notice",local("过敏备注","Allergy note"),local("1 人海鲜过敏（已填备注；尚未获商家确认可满足）","1 guest has a seafood allergy. The note is included; the restaurant has not confirmed that it can accommodate it.")),
                new SurfaceSpec.Block("fact",local("费用与条件","Fees and conditions"),local("预约免费，未选团购或收费项；超时未到店商家可能取消预订。","The reservation is free, with no paid add-ons selected. The restaurant may cancel it if guests arrive late.")),
                new SurfaceSpec.Block("text","",local("合成布局测试，不会提交真实预订。","Synthetic layout test. No real reservation will be submitted."))));
    }
    @Override public void onDestroy() { client.close(); super.onDestroy(); }
}
