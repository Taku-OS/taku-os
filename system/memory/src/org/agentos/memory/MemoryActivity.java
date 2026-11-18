package org.agentos.memory;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.text.InputFilter;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import static org.agentos.memory.MemoryUi.*;

/** Native reading and retrieval UI for the independent memory app. */
public final class MemoryActivity extends Activity {
 MemoryStore store;
 MemoryUi ui;
 MemoryCopy copy;
 LinearLayout root,body,list;
 ScrollView scroll;
 TextView captureStatus,resultCount;
 EditText search;
 String tab="memory",screen="home",query="",category="",sourceFilter="",detailId="";
 String renderedKey="";
 int visible=20;
 boolean editing,infoExpanded,restoringScroll;
 int pendingScroll;
 final Map<String,Integer> positions=new HashMap<>();
 final Handler main=new Handler(Looper.getMainLooper());
 final ExecutorService io=Executors.newSingleThreadExecutor();
 final Set<String> refreshing=new HashSet<>();
 final Runnable refresh=new Runnable(){public void run(){if(tab.equals("suggestions"))refreshTasks();main.postDelayed(this,3000);}};
 final ContentObserver observer=new ContentObserver(main){public void onChange(boolean self){refreshContent();}};

 int dp(int n){return ui.dp(n);}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);ui=new MemoryUi(this);copy=new MemoryCopy(this);store=MemoryStore.get(this);
  if(saved!=null){tab=saved.getString("tab","memory");query=saved.getString("query","");category=saved.getString("category","");sourceFilter=saved.getString("source","");screen=saved.getString("screen","home");detailId=saved.getString("id","");visible=saved.getInt("visible",20);positions.put(key(),saved.getInt("scroll",0));}
  if(saved==null&&"settings".equals(getIntent().getStringExtra("page")))tab="settings";
  if(saved==null&&"suggestions".equals(getIntent().getStringExtra("tab")))tab="suggestions";   // Opened from a proactive-reminder notification.
  tab=switch(tab){case "记忆"->"memory";case "建议"->"suggestions";case "设置"->"settings";default->tab;};
  MemoryMaintenance.schedule(this);AnalysisJob.schedule(this);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(Color.WHITE);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  getWindow().getDecorView();
  getWindow().getInsetsController().setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
  getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::goBack);
  render();
 }
 @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("tab",tab);out.putString("query",query);out.putString("category",category);out.putString("source",sourceFilter);out.putString("screen",screen);out.putString("id",detailId);out.putInt("visible",visible);out.putInt("scroll",scroll==null?0:scroll.getScrollY());}
 @Override public void onStart(){super.onStart();getContentResolver().registerContentObserver(MemoryStore.URI,true,observer);refreshContent();refreshSystemModel();main.post(refresh);
  if(store.feedOn()&&FeedCards.available(this)){Context app=getApplicationContext();ProactiveNotice.FEED.execute(()->ProactiveNotice.readBack(app,store,store.now()));}}   // Read back Home reactions; store changes notify the UI to refresh.
 @Override public void onStop(){main.removeCallbacks(refresh);getContentResolver().unregisterContentObserver(observer);super.onStop();}
 @Override public void onDestroy(){io.shutdownNow();super.onDestroy();}
 String key(){return screen.equals("home")?tab:screen+detailId;}
 void savePosition(){if(scroll!=null&&!renderedKey.isEmpty())positions.put(renderedKey,restoringScroll?pendingScroll:scroll.getScrollY());}
 void goBack(){if(!screen.equals("home")){screen="home";infoExpanded=false;render();}else finish();}
 void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 void hideKeyboard(){View v=getCurrentFocus();if(v!=null)((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);}
 interface Throwing{JSONObject run()throws Exception;}
 interface Result{void done(JSONObject r);}
 void async(Throwing call,Result result){io.execute(()->{try{JSONObject r=call.run();main.post(()->{if(!isFinishing()&&!isDestroyed())result.done(r);});}catch(Exception e){main.post(()->{if(!isFinishing()&&!isDestroyed())message(e.getMessage()==null?s(R.string.service_unavailable):e.getMessage());});}});}
 void add(LinearLayout parent,View child,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);p.bottomMargin=dp(bottom);parent.addView(child,p);}
 void gap(LinearLayout parent,int size){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(size)));}
 void rule(LinearLayout parent){View v=new View(this);v.setBackgroundColor(LINE);parent.addView(v,new LinearLayout.LayoutParams(-1,dp(1)));}
 TextView label(String s){return ui.bold(s,13,MUTED);}
 LinearLayout card(LinearLayout parent){LinearLayout c=ui.col();c.setBackground(ui.shape(Color.WHITE,20,LINE));c.setPadding(dp(18),dp(18),dp(18),dp(18));add(parent,c,0,12);return c;}
 void refreshContent(){
  if(editing||root==null||isFinishing())return;
  // Keep the keyboard, cursor and search field instance when capture or analysis updates arrive.
  if(screen.equals("home")&&tab.equals("memory")&&list!=null){try{updateCaptureStatus();renderList();}catch(Exception e){message(s(R.string.read_error));}}
  else render();
 }
 void render(){
  if(isFinishing()||editing)return;
  savePosition();search=null;list=null;captureStatus=null;resultCount=null;
  root=ui.col();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
  try{
   if(screen.equals("home"))homeHeader();else readingHeader();
   scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
   body=ui.col();body.setPadding(dp(22),dp(12),dp(22),dp(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
   switch(screen){case "detail":detailPage();break;case "diagnostics":diagnostics();break;case "board":boardPage();break;case "profile":profilePage();break;default:switch(tab){case "settings":settings();break;case "suggestions":suggestions();break;default:list=ui.col();body.addView(list);renderList();}}
   if(screen.equals("home"))bottomNav();
  }catch(Exception e){if(body!=null)empty(body,"document",s(R.string.read_unavailable),e.getMessage()==null?s(R.string.try_again):e.getMessage(),s(R.string.back_memories),()->{screen="home";tab="memory";render();});}
  setContentView(root);renderedKey=key();int position=positions.getOrDefault(renderedKey,0);ScrollView target=scroll;pendingScroll=position;restoringScroll=true;if(target!=null)target.post(()->{target.scrollTo(0,position);if(scroll==target)restoringScroll=false;});
 }
 void homeHeader()throws Exception{
  LinearLayout h=ui.col();h.setPadding(dp(22),dp(18),dp(22),dp(6));
  LinearLayout row=ui.row();row.addView(ui.bold(tabLabel(tab),30,INK),new LinearLayout.LayoutParams(0,-2,1));row.addView(ui.brand(30,true));h.addView(row);
  add(h,ui.text(switch(tab){case "suggestions"->s(R.string.suggestions_subtitle);case "settings"->s(R.string.settings_subtitle);default->s(R.string.memories_subtitle);},13,MUTED),8,tab.equals("memory")?16:6);
  if(tab.equals("memory")){
   LinearLayout box=ui.row();box.setPadding(dp(15),0,dp(4),0);box.setBackground(ui.shape(Color.WHITE,16,LINE));box.addView(ui.icon("search",MUTED,20));
   search=new EditText(this);search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint(s(R.string.search_hint));search.setSingleLine(true);search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(dp(10),dp(14),0,dp(14));search.setText(query);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);box.addView(search,new LinearLayout.LayoutParams(0,-2,1));
   View clear=ui.iconButton("close",s(R.string.clear_search),()->search.setText(""));clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);box.addView(clear);h.addView(box);
   search.setOnEditorActionListener((v,a,event)->{hideKeyboard();search.clearFocus();return true;});
   search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();visible=20;clear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);try{renderList();scroll.scrollTo(0,0);}catch(Exception ignored){}}});
   HorizontalScrollView filters=new HorizontalScrollView(this);filters.setHorizontalScrollBarEnabled(false);LinearLayout chips=ui.row();
   for(String c:new String[]{"","工作","生活","出行","学习","其他","未分类"}){TextView chip=ui.pill(c.isEmpty()?s(R.string.all_categories):categoryLabel(c),c.equals(category));chip.setContentDescription(s(R.string.category_filter,c.isEmpty()?s(R.string.all_categories):categoryLabel(c)));chip.setOnClickListener(v->{hideKeyboard();category=c;visible=20;positions.put("memory",0);if(scroll!=null)scroll.scrollTo(0,0);render();});chips.addView(chip);if(c.equals(category))filters.post(()->filters.scrollTo(Math.max(0,chip.getLeft()-(filters.getWidth()-chip.getWidth())/2),0));}
   filters.addView(chips);add(h,filters,12,10);
   LinearLayout status=ui.row();captureStatus=ui.text("",12,MUTED);status.addView(captureStatus,new LinearLayout.LayoutParams(0,-2,1));updateCaptureStatus();
   TextView filter=ui.pill(sourceFilter.isEmpty()?s(R.string.all_sources_short):source(sourceFilter)+"⌄",!sourceFilter.isEmpty());filter.setMaxLines(1);filter.setEllipsize(TextUtils.TruncateAt.END);filter.setMaxWidth(dp(160));filter.setMinHeight(dp(44));filter.setContentDescription(s(R.string.filter_sources));filter.setOnClickListener(v->sourceFilter());status.addView(filter);h.addView(status);
  }
  root.addView(h);
 }
 void updateCaptureStatus()throws Exception{if(captureStatus==null)return;JSONObject st=store.status();String state=!st.getBoolean("enabled")?s(R.string.capture_paused):!st.getBoolean("observationConnected")?s(R.string.capture_disconnected):st.getJSONArray("sources").length()==0?s(R.string.capture_no_sources):s(R.string.capture_active);captureStatus.setText(s(R.string.capture_count,state,st.getInt("count")));captureStatus.setTextColor(state.equals(s(R.string.capture_active))?GREEN:MUTED);}
 void bottomNav(){
  LinearLayout nav=ui.row();nav.setBackgroundColor(Color.WHITE);nav.setPadding(dp(10),dp(8),dp(10),dp(5));
  for(String t:new String[]{"memory","suggestions","settings"}){boolean selected=t.equals(tab);LinearLayout item=ui.col();item.setGravity(Gravity.CENTER);item.setPadding(0,dp(3),0,dp(3));item.setBackground(ui.touch(Color.TRANSPARENT,18,0));item.addView(ui.icon(t.equals("memory")?"memory":t.equals("suggestions")?"spark":"settings",selected?ORANGE:MUTED,23));TextView text=ui.text(tabLabel(t),11,selected?ORANGE:MUTED);add(item,text,5,0);text.setGravity(Gravity.CENTER);item.setContentDescription(tabLabel(t));item.setSelected(selected);item.setFocusable(true);item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);item.setOnClickListener(v->{hideKeyboard();tab=t;screen="home";render();});nav.addView(item,new LinearLayout.LayoutParams(0,dp(58),1));}
  rule(root);root.addView(nav);
 }
 void readingHeader(){LinearLayout bar=ui.row();bar.setPadding(dp(6),dp(4),dp(8),dp(4));bar.addView(ui.iconButton("back",s(R.string.back),this::goBack));bar.addView(ui.bold(s(switch(screen){case "detail"->R.string.memory_detail;case "board"->R.string.board;case "profile"->R.string.user_card;default->R.string.diagnostics;}),16,INK),new LinearLayout.LayoutParams(0,-2,1));bar.addView(ui.brand(24,false));if(screen.equals("detail"))bar.addView(ui.iconButton("more",s(R.string.more_actions),this::detailMenu));root.addView(bar);}
 String s(int id,Object... args){return getString(id,args);}
 String tabLabel(String key){return s(switch(key){case "suggestions"->R.string.tab_suggestions;case "settings"->R.string.tab_settings;default->R.string.tab_memory;});}
 String categoryLabel(String key){return s(switch(key){case "工作"->R.string.category_work;case "生活"->R.string.category_life;case "出行"->R.string.category_travel;case "学习"->R.string.category_learn;case "其他"->R.string.category_other;default->R.string.category_unclassified;});}
 String time(long at){return android.text.format.DateFormat.format("MM-dd HH:mm",at).toString();}
 String source(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return pkg;}}
 String date(long at){Calendar today=Calendar.getInstance(),day=Calendar.getInstance();day.setTimeInMillis(at);if(today.get(Calendar.YEAR)==day.get(Calendar.YEAR)&&today.get(Calendar.DAY_OF_YEAR)==day.get(Calendar.DAY_OF_YEAR))return s(R.string.today);today.add(Calendar.DATE,-1);if(today.get(Calendar.YEAR)==day.get(Calendar.YEAR)&&today.get(Calendar.DAY_OF_YEAR)==day.get(Calendar.DAY_OF_YEAR))return s(R.string.yesterday);return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM,getResources().getConfiguration().getLocales().get(0)).format(new java.util.Date(at));}
 String analysis(JSONObject r){return switch(r.optString("analysis")){case "ready"->s(R.string.classified);case "corrected"->s(R.string.corrected);case "error"->s(R.string.analysis_retry);default->s(R.string.local_record);};}
 String snippet(String body,int max){String s=body.replaceAll("\\s+"," ").trim();return s.length()>max?s.substring(0,max)+"…":s;}
 String excerpt(JSONObject r,int max){return copy.excerpt(r,max);}

 void sourceLine(LinearLayout parent,JSONObject r)throws Exception{
  LinearLayout row=ui.row();ImageView icon=new ImageView(this);try{icon.setImageDrawable(getPackageManager().getApplicationIcon(r.getString("source")));}catch(Exception e){icon.setImageDrawable(ui.shape(LINE,7,0));}row.addView(icon,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView name=ui.text(source(r.getString("source")),12,MUTED);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(8);row.addView(name,p);row.addView(ui.text(android.text.format.DateFormat.format("HH:mm",r.getLong("first_seen")).toString(),12,MUTED));parent.addView(row);
 }
 void renderList()throws Exception{
  if(list==null)return;list.removeAllViews();JSONObject page=store.browse(query,category,sourceFilter,Math.min(visible,100),0);int total=page.getInt("total");
  boolean filtered=!query.isBlank()||!category.isEmpty()||!sourceFilter.isEmpty();
  if(filtered){resultCount=ui.text(s(R.string.results_count,query.isBlank()?s(R.string.filtered_results):s(R.string.search_results),total),12,MUTED);add(list,resultCount,0,16);}
  if(total==0){empty(list,"search",filtered?s(R.string.no_results):s(R.string.start_browsing),filtered?s(R.string.search_help):s(R.string.capture_help),filtered?s(R.string.clear_filters):s(R.string.setup_capture),()->{if(filtered){query="";category="";sourceFilter="";visible=20;}else tab="settings";render();});return;}
  JSONArray records=page.getJSONArray("items");if(visible>100){records=new JSONArray();for(int offset=0;offset<Math.min(visible,total);offset+=100){JSONArray part=store.browse(query,category,sourceFilter,100,offset).getJSONArray("items");for(int i=0;i<part.length();i++)records.put(part.getJSONObject(i));}}
  String previous="";int count=Math.min(visible,records.length());
  for(int i=0;i<count;i++){
   JSONObject r=records.getJSONObject(i);String group=date(r.getLong("last_seen"));if(!group.equals(previous)){add(list,label(group),i==0?0:10,12);previous=group;}
   LinearLayout c=card(list);c.setBackground(ui.touch(Color.WHITE,20,LINE));sourceLine(c,r);TextView title=ui.bold(copy.title(r),17,INK);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);add(c,title,13,7);
   TextView excerpt=ui.text(excerpt(r,180),14,MUTED);excerpt.setMaxLines(3);excerpt.setEllipsize(TextUtils.TruncateAt.END);c.addView(excerpt);
   LinearLayout footer=ui.row();TextView tag=ui.text(categoryLabel(r.getString("category")),11,r.getString("category").equals("未分类")?MUTED:GREEN);tag.setPadding(dp(8),dp(4),dp(8),dp(4));tag.setBackground(ui.shape(BG,6,0));footer.addView(tag);TextView state=ui.text(analysis(r),11,MUTED);state.setGravity(Gravity.RIGHT);footer.addView(state,new LinearLayout.LayoutParams(0,-2,1));add(c,footer,14,0);
   String id=r.getString("id");c.setFocusable(true);c.setOnClickListener(v->detail(id));
  }
  if(count<total)add(list,ui.action(s(R.string.load_more,total-count),false,()->{int y=scroll.getScrollY();visible+=20;try{renderList();scroll.post(()->scroll.scrollTo(0,y));}catch(Exception e){message(e.getMessage());}}),6,0);
  else if(total>2){TextView end=ui.text(s(R.string.all_shown,total),12,MUTED);end.setGravity(Gravity.CENTER);add(list,end,8,0);}
 }
 void empty(LinearLayout parent,String icon,String title,String description,String action,Runnable click){
  LinearLayout area=ui.col();area.setGravity(Gravity.CENTER);area.setPadding(dp(22),dp(44),dp(22),dp(30));FrameLayout circle=new FrameLayout(this);circle.setBackground(ui.shape(SOFT,25,0));circle.addView(ui.icon(icon,ORANGE,24),new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));area.addView(circle,new LinearLayout.LayoutParams(dp(64),dp(64)));TextView heading=ui.bold(title,20,INK);heading.setGravity(Gravity.CENTER);add(area,heading,22,10);TextView desc=ui.text(description,14,MUTED);desc.setGravity(Gravity.CENTER);add(area,desc,0,22);if(action!=null)area.addView(ui.action(action,false,click));parent.addView(area);
 }
 void sourceFilter(){List<String> pkgs=store.recordedSources();String[] labels=new String[pkgs.size()+1];labels[0]=s(R.string.all_sources);int selected=0;for(int i=0;i<pkgs.size();i++){labels[i+1]=source(pkgs.get(i));if(pkgs.get(i).equals(sourceFilter))selected=i+1;}new AlertDialog.Builder(this).setTitle(s(R.string.source_filter_title)).setSingleChoiceItems(labels,selected,(d,n)->{sourceFilter=n==0?"":pkgs.get(n-1);visible=20;positions.put("memory",0);if(scroll!=null)scroll.scrollTo(0,0);d.dismiss();render();}).setNegativeButton(s(R.string.cancel),null).show();}
 void detail(String id){hideKeyboard();detailId=id;screen="detail";infoExpanded=false;render();}
 void detailPage()throws Exception{
  JSONObject r=store.read(detailId,true);TextView title=ui.bold(copy.title(r),25,INK);title.setTextIsSelectable(true);add(body,title,8,18);sourceLine(body,r);add(body,ui.text(date(r.getLong("first_seen"))+" · "+categoryLabel(r.getString("category"))+" · "+analysis(r),12,MUTED),10,22);rule(body);
  TextView content=ui.text(r.optString("text",r.getString("body")),16,INK);content.setTextIsSelectable(true);content.setLineSpacing(dp(7),1.1f);add(body,content,24,28);
  LinearLayout info=card(body);LinearLayout row=ui.row();row.addView(ui.icon("document",MUTED,19));TextView label=ui.bold(s(R.string.record_info),14,INK);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(9);row.addView(label,lp);row.addView(ui.text(infoExpanded?s(R.string.collapse):s(R.string.expand),12,MUTED));info.addView(row);info.setOnClickListener(v->{infoExpanded=!infoExpanded;render();});
  if(infoExpanded){add(info,ui.text(s(R.string.saved_title,r.getString("title")),12,MUTED),14,0);add(info,ui.text(s(R.string.provenance,android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",r.getLong("first_seen")),android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",r.getLong("last_seen")),source(r.getString("source")),r.getString("source"),r.getInt("revision"),detailId,analysis(r)),12,MUTED),14,0);}
  add(body,ui.action(s(R.string.correct_content),false,()->correct(r)),4,14);TextView note=ui.text(s(R.string.original_note),12,MUTED);add(body,note,0,6);
 }
 void detailMenu(){new AlertDialog.Builder(this).setTitle(s(R.string.memory_actions)).setItems(new String[]{s(R.string.correct_content),s(R.string.view_history),s(R.string.delete_memory)},(d,n)->{try{JSONObject r=store.read(detailId,true);if(n==0)correct(r);else if(n==1)history(r);else confirmDelete(detailId);}catch(Exception e){message(e.getMessage());}}).show();}
 void history(JSONObject r)throws Exception{JSONArray a=r.getJSONArray("history");if(a.length()==0){new AlertDialog.Builder(this).setTitle(s(R.string.history)).setMessage(s(R.string.no_edits)).setPositiveButton(s(R.string.done),null).show();return;}LinearLayout page=ui.col();page.setPadding(dp(22),dp(12),dp(22),dp(20));for(int i=0;i<a.length();i++){JSONObject h=a.getJSONObject(i);add(page,ui.bold(s(R.string.history_version,h.getInt("revision"),time(h.getLong("at"))),14,INK),8,10);add(page,ui.text(h.getString("title")+"\n\n"+h.getString("body"),14,MUTED),0,20);}ScrollView view=new ScrollView(this);view.addView(page);new AlertDialog.Builder(this).setTitle(s(R.string.history)).setView(view).setPositiveButton(s(R.string.done),null).show();}
 void confirmDelete(String id){new AlertDialog.Builder(this).setTitle(s(R.string.delete_title)).setMessage(s(R.string.delete_help)).setPositiveButton(s(R.string.delete),(d,n)->{try{store.forget(id);screen="home";render();message(s(R.string.deleted));}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();}
 void correct(JSONObject r){try{
  editing=true;LinearLayout fields=ui.col();fields.setPadding(dp(22),dp(6),dp(22),dp(12));add(fields,ui.text(s(R.string.edit_help),12,MUTED),0,12);EditText title=new EditText(this),content=new EditText(this);title.setHint(s(R.string.title));title.setText(copy.title(r));title.setTextSize(16);title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});content.setHint(s(R.string.content));content.setText(r.optString("text",r.getString("body")));content.setTextSize(15);content.setMinLines(4);content.setMaxLines(8);content.setGravity(Gravity.TOP);content.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8000)});fields.addView(title);fields.addView(content);ScrollView view=new ScrollView(this);view.addView(fields);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(s(R.string.edit_memory)).setView(view).setPositiveButton(s(R.string.save),null).setNegativeButton(s(R.string.cancel),null).create();dialog.setOnDismissListener(d->{editing=false;render();});dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{store.update(r.getString("id"),r.getInt("revision"),title.getText().toString(),content.getText().toString());dialog.dismiss();}catch(Exception e){message(e.getMessage());}}));dialog.show();
 }catch(Exception e){editing=false;message(e.getMessage());}}
 String reactions(JSONArray r){
  if(r==null)return "";List<String> out=new ArrayList<>();
  for(int i=0;i<r.length();i++)switch(r.optString(i)){case "selected"->out.add(s(R.string.feed_reaction_selected));case "asked"->out.add(s(R.string.feed_reaction_asked));case "opened"->out.add(s(R.string.feed_reaction_opened));case "pinned"->out.add(s(R.string.feed_reaction_pinned));case "removed"->out.add(s(R.string.feed_reaction_removed));default->{}}
  return String.join(s(R.string.feed_reaction_sep),out);
 }
 String taskState(String s){return switch(s){case "completed"->s(R.string.task_completed);case "failed"->s(R.string.task_failed);case "needs_user"->s(R.string.task_needs_user);case "unconfirmed"->s(R.string.task_unconfirmed);case "reserved"->s(R.string.task_reserved);default->s(R.string.task_processing);};}
 void refreshTasks(){try{JSONArray records=store.suggestionRecords();for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);String id=r.getString("id"),task=r.getString("task_id"),state=r.getString("task_state");if(task.isEmpty()||Set.of("completed","failed","needs_user","unconfirmed").contains(state)||!refreshing.add(id))continue;io.execute(()->{try{JSONObject result=BrokerClient.call("task",new JSONObject().put("id",id));store.task(id,task,result.optString("state","unconfirmed"));}catch(Exception ignored){}finally{main.post(()->refreshing.remove(id));}});}}catch(Exception ignored){}}
 boolean offer(JSONObject r)throws Exception{return r.getString("task_id").isEmpty()&&MemoryStore.actionable(r.getString("decision"))&&r.getLong("suggested_at")>0&&System.currentTimeMillis()-r.getLong("suggested_at")<=300000&&store.prefs.getBoolean("analysisEnabled",false)&&store.prefs.getBoolean("queryEnabled",false)&&store.prefs.getBoolean("proactive",true)&&store.sourceAllowed(r.getString("source"))&&r.getDouble("confidence")>=.75&&r.getString("model").equals(store.effectiveModel());}
 void suggestions()throws Exception{
  refreshTasks();LinearLayout intro=card(body);intro.addView(ui.bold(s(R.string.suggestions_intro),17,INK));add(intro,ui.text(s(R.string.suggestions_help),14,MUTED),8,0);
  // Proactive speech from the last 7 days: bedtime briefs and messages related to memories.
  JSONArray said=store.nudges(System.currentTimeMillis()-7L*86400000L,30);
  if(said.length()>0){add(body,label(s(R.string.nudges_recent)),14,12);
   for(int i=0;i<said.length();i++){JSONObject n=said.getJSONObject(i);LinearLayout c=card(body);
    add(c,ui.text(time(n.getLong("at"))+" · "+s(n.getString("kind").equals("evening")?R.string.nudge_kind_evening:R.string.nudge_kind_message),12,MUTED),0,6);
    c.addView(ui.bold(ProactiveNotice.heading(this,n.getString("kind"),n.getString("title")),16,INK));add(c,ui.text(n.getString("body"),14,INK),8,0);
    String reacted=reactions(n.optJSONArray("reactions"));if(!reacted.isEmpty())add(c,ui.text(s(R.string.nudge_feed_line,reacted),12,MUTED),8,0);   // What the user did with this card on Home.
    long nid=n.getLong("id");
    if(n.optString("task").isEmpty())add(c,ui.action(s(R.string.nudge_ask),false,()->async(()->BrokerClient.call("nudgeTask",new JSONObject().put("id",nid)),v->{message(s(R.string.nudge_asked));render();})),12,0);
    else add(c,ui.action(s(R.string.view_agent_result),false,()->async(()->BrokerClient.call("nudgeTaskState",new JSONObject().put("id",nid)),v->showResult(v))),12,0);}
  }
  JSONArray all=store.suggestionRecords();int offers=0,done=0;for(int pass=0;pass<2;pass++){
   boolean heading=false;
   for(int i=0;i<all.length();i++){
    JSONObject r=all.getJSONObject(i);boolean accepted=!r.getString("task_id").isEmpty();if(pass==0?!offer(r):!accepted)continue;
    if(!heading){add(body,label(pass==0?s(R.string.available_actions):s(R.string.accepted_suggestions)),14,12);heading=true;}
    if(pass==0)offers++;else done++;LinearLayout c=card(body);sourceLine(c,r);add(c,ui.bold(copy.suggestionTitle(r),17,INK),14,8);TextView excerpt=ui.text(copy.suggestionSummary(r),14,MUTED);excerpt.setMaxLines(5);excerpt.setEllipsize(TextUtils.TruncateAt.END);c.addView(excerpt);String id=r.getString("id");int revision=r.getInt("revision");
    if(!accepted)add(c,ui.action(copy.action(r),true,()->async(()->BrokerClient.call("accept",new JSONObject().put("id",id).put("revision",revision)),v->{message(s(R.string.sent_agent));render();})),16,8);
    else{add(c,ui.text("● "+taskState(r.getString("task_state")),12,GREEN),14,12);add(c,ui.action(s(R.string.view_agent_result),false,()->async(()->BrokerClient.call("task",new JSONObject().put("id",id)),v->showResult(v))),0,6);}
    TextView original=ui.text(s(R.string.view_original),13,ORANGE);original.setGravity(Gravity.CENTER);original.setMinHeight(dp(44));original.setOnClickListener(v->detail(id));c.addView(original);
   }
  }
  if(offers==0&&done==0&&said.length()==0)empty(body,"spark",s(R.string.no_suggestions),store.prefs.getBoolean("proactive",true)?s(R.string.suggestions_empty):s(R.string.suggestions_paused),s(R.string.view_memories),()->{tab="memory";render();});
 }
 void showResult(JSONObject result){LinearLayout page=ui.col();page.setPadding(dp(22),dp(14),dp(22),dp(24));page.addView(ui.bold(taskState(result.optString("state")),14,GREEN));TextView text=ui.text(result.optString("result.txt",s(R.string.agent_wait)),15,INK);text.setText(MemoryResult.render(result.optString("result.txt",s(R.string.agent_wait))));text.setTextIsSelectable(true);add(page,text,18,0);ScrollView view=new ScrollView(this);view.addView(page);new AlertDialog.Builder(this).setTitle(s(R.string.agent_result)).setView(view).setPositiveButton(s(R.string.done),null).show();}
 void section(String title){add(body,label(title),14,12);}
 void toggle(LinearLayout parent,String title,String summary,String key,boolean value){
  LinearLayout item=ui.col();item.setPadding(0,dp(8),0,dp(8));LinearLayout row=ui.row();row.addView(ui.bold(title,16,INK),new LinearLayout.LayoutParams(0,-2,1));Switch control=new Switch(this);control.setContentDescription(title);control.setMinHeight(dp(48));control.setButtonTintList(null);control.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ORANGE,0xffb7bdb3}));control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xfff6c5b2,LINE}));control.setChecked(value);row.addView(control);row.setOnClickListener(v->control.performClick());item.addView(row);add(item,ui.text(summary,12,MUTED),0,8);parent.addView(item);
  control.setOnCheckedChangeListener((v,on)->{
   if(key.equals("analysisEnabled")&&on)new AlertDialog.Builder(this).setTitle(s(R.string.enable_analysis)).setMessage(s(R.string.analysis_consent)).setPositiveButton(s(R.string.enable),(d,w)->configure(key,true)).setNegativeButton(s(R.string.cancel),null).setOnDismissListener(d->render()).show();
   else if(key.equals("nudges")&&on)new AlertDialog.Builder(this).setTitle(s(R.string.nudges)).setMessage(s(R.string.nudges_consent)).setPositiveButton(s(R.string.enable),(d,w)->{configure(key,true);if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},7);}).setNegativeButton(s(R.string.cancel),null).setOnDismissListener(d->render()).show();
   else if(key.equals("enabled")&&on)new AlertDialog.Builder(this).setTitle(s(R.string.enable_capture)).setMessage(s(R.string.capture_consent)).setPositiveButton(s(R.string.enable),(d,w)->configure(key,true)).setNegativeButton(s(R.string.cancel),null).setOnDismissListener(d->render()).show();
   else configure(key,on);
  });
 }
 void configure(String key,boolean value){try{store.configure(new JSONObject().put(key,value));render();}catch(Exception e){message(e.getMessage());render();}}
 void settingRow(LinearLayout parent,String title,String subtitle,Runnable click){LinearLayout row=ui.row();row.setPadding(0,dp(14),0,dp(14));row.setMinimumHeight(dp(60));row.setBackground(ui.touch(Color.TRANSPARENT,10,0));LinearLayout words=ui.col();words.addView(ui.bold(title,15,INK));if(!subtitle.isEmpty())add(words,ui.text(subtitle,12,MUTED),6,0);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));row.addView(ui.icon("chevron",MUTED,18));row.setOnClickListener(v->click.run());parent.addView(row);}
 void settings()throws Exception{
  JSONObject cfg=store.status();section(s(R.string.capture_section));LinearLayout capture=card(body);toggle(capture,s(R.string.auto_capture),s(R.string.capture_summary),"enabled",cfg.getBoolean("enabled"));rule(capture);settingRow(capture,s(R.string.capture_apps),s(R.string.selected_apps,cfg.getJSONArray("sources").length()),this::sources);rule(capture);settingRow(capture,s(R.string.observation_service),cfg.getBoolean("observationConnected")?s(R.string.connected):s(R.string.a11y_enable),()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));rule(capture);settingRow(capture,s(R.string.notification_access),notificationAccess()?s(R.string.notification_on):s(R.string.notification_off),()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
  section(s(R.string.analysis_section));LinearLayout analyze=card(body);toggle(analyze,s(R.string.model_analysis),s(R.string.analysis_summary),"analysisEnabled",cfg.getBoolean("analysisEnabled"));add(analyze,ui.text(s(R.string.queue_counts,cfg.getInt("analysisQueued"),cfg.getInt("analysisInFlight")),12,cfg.getInt("analysisQueued")>0?ORANGE:MUTED),0,12);rule(analyze);settingRow(analyze,s(R.string.analysis_model),cfg.optBoolean("followsSystem")?s(R.string.follow_system_model,modelName(cfg.getString("model"))):modelName(cfg.getString("model")),this::models);rule(analyze);toggle(analyze,s(R.string.agent_access),s(R.string.agent_access_summary),"queryEnabled",cfg.getBoolean("queryEnabled"));rule(analyze);toggle(analyze,s(R.string.proactive),s(R.string.proactive_summary),"proactive",cfg.getBoolean("proactive"));
  settingRow(analyze,s(R.string.system_ai),s(R.string.system_ai_summary),this::systemAi);
  organizeSection(cfg);
  section(s(R.string.data_section));LinearLayout data=card(body);settingRow(data,s(R.string.language),languageName(),this::language);rule(data);settingRow(data,s(R.string.retention),cfg.getInt("retentionDays")==0?s(R.string.retention_forever_summary):s(R.string.retention_summary,cfg.getInt("retentionDays")),this::retention);rule(data);settingRow(data,s(R.string.diagnostics),s(R.string.runtime_summary),()->{screen="diagnostics";render();});
  LinearLayout note=ui.row();note.addView(ui.icon("shield",MUTED,20));TextView wording=ui.text(s(R.string.privacy_footer),11,MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(8);note.addView(wording,p);add(body,note,12,0);
 }
 String languageName(){android.os.LocaleList selected=getSystemService(android.app.LocaleManager.class).getApplicationLocales();return selected.isEmpty()?s(R.string.language_system):selected.get(0).getLanguage().equals("zh")?"中文":"English";}
 void language(){String[] codes={"","en","zh-CN"};android.os.LocaleList selected=getSystemService(android.app.LocaleManager.class).getApplicationLocales();int current=selected.isEmpty()?0:selected.get(0).getLanguage().equals("zh")?2:1;new AlertDialog.Builder(this).setTitle(s(R.string.language)).setSingleChoiceItems(new String[]{s(R.string.language_system),"English","中文"},current,(d,n)->{d.dismiss();getSystemService(android.app.LocaleManager.class).setApplicationLocales(android.os.LocaleList.forLanguageTags(codes[n]));}).setNegativeButton(s(R.string.cancel),null).show();}
 boolean notificationAccess(){try{return getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(new ComponentName(this,NotificationTap.class));}catch(Exception e){return false;}}
 String updated(long t){return t==0?s(R.string.not_organized):s(R.string.updated_at,time(t));}
 // Organized board and user card: users can inspect, delete, or clear and rebuild.
 void organizeSection(JSONObject cfg)throws Exception{
  section(s(R.string.organize_section));LinearLayout org=card(body);
  toggle(org,s(R.string.nightly_organize),s(R.string.nightly_organize_summary),"autoOrganize",cfg.getBoolean("autoOrganize"));rule(org);
  JSONObject bv=store.boardView(),pv=store.profileView();JSONArray active=bv.getJSONObject("board").optJSONArray("active");JSONObject card=pv.getJSONObject("profile");
  settingRow(org,s(R.string.board),s(R.string.board_summary,active==null?0:active.length(),updated(bv.getLong("updated"))),()->{screen="board";render();});rule(org);
  settingRow(org,s(R.string.user_card),s(R.string.card_summary,card.optJSONArray("me").length(),card.optJSONArray("people").length(),updated(pv.getLong("updated"))),()->{screen="profile";render();});rule(org);
  toggle(org,s(R.string.agent_board),s(R.string.agent_board_summary),"agentBoard",cfg.getBoolean("agentBoard"));rule(org);
  toggle(org,s(R.string.agent_card),s(R.string.agent_card_summary),"agentProfile",cfg.getBoolean("agentProfile"));rule(org);
  toggle(org,s(R.string.nudges),s(R.string.nudges_summary),"nudges",cfg.optBoolean("nudges"));rule(org);
  if(cfg.optBoolean("nudges")&&FeedCards.available(this)){toggle(org,s(R.string.feed_cards),s(R.string.feed_cards_summary),"feedCards",cfg.optBoolean("feedCards"));rule(org);
   if(cfg.optBoolean("feedCards")){toggle(org,s(R.string.feed_describe),s(R.string.feed_describe_summary),"feedDescribe",cfg.optBoolean("feedDescribe"));rule(org);}}
  settingRow(org,s(R.string.your_name),store.userName().equals("我")?s(R.string.your_name_default):store.userName(),this::rename);rule(org);
  settingRow(org,s(R.string.organize_model),organizeModelLabel(cfg.optString("organizeModel")),this::organizeModels);rule(org);
  String state=Organizer.state==null?s(R.string.not_organized):Organizer.state;
  settingRow(org,s(R.string.organize_now),Organizer.running?state:cfg.optBoolean("rebuildPending")?s(R.string.rebuild_pending):state,()->{message(Organizer.start(this,0)?s(R.string.organize_started):s(R.string.organize_running));render();});
  JSONObject last=store.status().optJSONObject("lastOrganize");
  if(last!=null&&last.optLong("at")>0)add(org,ui.text(s(R.string.last_organize,tokens(last.optLong("tokens")),last.optString("models"),time(last.optLong("at"))),12,MUTED),2,10);
 }
 /** Token counts in the reader's scale: 万 in Chinese, thousands in English. */
 String tokens(long n){int unit=getResources().getInteger(R.integer.token_unit);return s(R.string.token_count,String.format(getResources().getConfiguration().getLocales().get(0),n>=unit*10?"%.0f":"%.1f",n/(double)unit));}
 String organizeModelLabel(String choice){return choice.isEmpty()?s(R.string.organize_model_recommended):choice.equals("system")?s(R.string.organize_model_system):choice;}
 void organizeModels(){async(()->BrokerClient.call("organizeModels",null),r->{try{
  JSONArray a=r.getJSONArray("models");JSONObject rec=r.getJSONObject("recommended");String sys=r.optString("system");
  String[] labels=new String[a.length()+2];String[] values=new String[a.length()+2];
  labels[0]=s(R.string.organize_model_recommended_detail,rec.optString("graph"),rec.optString("board"));values[0]="";
  // Following Taku AI requires the memory route group on Magpie, without fallback. Mark unavailability before selection to avoid repeated interrupted organization.
  boolean sysReady=r.optBoolean("systemReady",true);
  labels[1]=sysReady?s(R.string.organize_model_system_detail,modelName(sys)):s(R.string.organize_model_system_unavailable,modelName(sys));values[1]="system";
  for(int i=0;i<a.length();i++){labels[i+2]=a.getString(i);values[i+2]=a.getString(i);}
  new AlertDialog.Builder(this).setTitle(s(R.string.organize_model)).setItems(labels,(d,n)->{if(n==1&&!sysReady){message(s(R.string.organize_model_system_needs_magpie));return;}try{store.configure(new JSONObject().put("organizeModel",values[n]));render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();
 }catch(Exception e){message(s(R.string.catalog_invalid));}});}
 void rename(){EditText name=new EditText(this);name.setText(store.userName().equals("我")?"":store.userName());name.setHint(s(R.string.your_name_hint));name.setSingleLine(true);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});LinearLayout box=ui.col();box.setPadding(dp(22),dp(8),dp(22),0);box.addView(name);
  new AlertDialog.Builder(this).setTitle(s(R.string.your_name)).setMessage(s(R.string.your_name_help)).setView(box).setPositiveButton(s(R.string.save),(d,w)->{try{store.configure(new JSONObject().put("userName",name.getText().toString()));render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();}
 interface Act{void run()throws Exception;}
 void confirmDerived(String title,String text,Act act){new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(s(R.string.delete),(d,n)->{try{act.run();message(s(R.string.item_deleted));render();}catch(Exception e){message(e.getMessage());render();}}).setNegativeButton(s(R.string.cancel),null).show();}
 void confirmClear(){new AlertDialog.Builder(this).setTitle(s(R.string.clear_title)).setMessage(s(R.string.clear_help)).setPositiveButton(s(R.string.clear),(d,n)->{try{store.clearDerived();Organizer.start(this,0);message(s(R.string.cleared));render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();}
 LinearLayout itemRow(LinearLayout parent,String text,int size,int color,String sub,String label,Runnable remove){
  LinearLayout row=ui.row();row.setPadding(0,dp(6),0,dp(6));LinearLayout words=ui.col();TextView t=ui.text(text,size,color);t.setTextIsSelectable(true);words.addView(t);if(sub!=null&&!sub.isEmpty())add(words,ui.text(sub,12,MUTED),5,0);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
  if(remove!=null)row.addView(ui.iconButton("close",label,remove));parent.addView(row);return row;
 }
 void boardPage()throws Exception{
  JSONObject v=store.boardView();JSONObject b=v.getJSONObject("board");
  add(body,ui.text(s(R.string.board_intro,updated(v.getLong("updated"))),12,MUTED),4,14);
  int shown=0;
  for(String part:new String[]{"active","done"}){JSONArray a=b.optJSONArray(part);if(a==null||a.length()==0)continue;section(s(part.equals("active")?R.string.board_active:R.string.board_done));
   for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);String nm=x.optString("name");shown++;LinearLayout c=card(body);
    itemRow(c,nm,16,INK,null,s(R.string.board_remove),()->confirmDerived(s(R.string.board_remove_title,nm),s(R.string.board_remove_help),()->store.deleteBoardItem(part,nm)));
    add(c,ui.text(x.optString("state"),14,INK),2,8);add(c,ui.text(s(R.string.board_aliases,Recall.join(x.optJSONArray("aliases")," / "),x.optString("last")),12,MUTED),0,0);}
  }
  if(shown==0)empty(body,"document",s(R.string.board_empty),s(R.string.board_empty_help),null,null);
  add(body,ui.action(s(R.string.clear_derived),false,this::confirmClear),16,6);
 }
 void profilePage()throws Exception{
  JSONObject v=store.profileView();JSONObject p=v.getJSONObject("profile");JSONArray me=p.optJSONArray("me"),ps=p.optJSONArray("people");
  add(body,ui.text(s(R.string.card_intro,updated(v.getLong("updated"))),12,MUTED),4,14);
  if(me!=null&&me.length()>0){section(s(R.string.card_me));LinearLayout c=card(body);
   for(int i=0;i<me.length();i++){JSONObject x=me.getJSONObject(i);String item=x.optString("item");if(i>0)rule(c);itemRow(c,item,15,INK,s(R.string.card_scene,x.optString("applies")),s(R.string.delete_item),()->confirmDerived(s(R.string.delete_item_title),item,()->store.deleteProfileItem("me",item,"")));}}
  if(ps!=null&&ps.length()>0){section(s(R.string.card_people));
   for(int i=0;i<ps.length();i++){JSONObject x=ps.getJSONObject(i);String name=x.optString("name");LinearLayout c=card(body);String aliases=Recall.join(x.optJSONArray("aliases")," / ");
    itemRow(c,s(R.string.card_person,name,x.optString("relation"),x.optString("closeness")),16,INK,aliases.isEmpty()?null:s(R.string.card_aliases,aliases),s(R.string.forget_person),()->confirmDerived(s(R.string.forget_person_title,name),s(R.string.forget_person_help),()->store.deleteProfileItem("person",name,"")));
    JSONArray notes=x.optJSONArray("notes");for(int k=0;notes!=null&&k<notes.length();k++){String note=notes.optString(k);itemRow(c,"· "+note,14,INK,null,s(R.string.delete_item),()->confirmDerived(s(R.string.delete_item_title),note,()->store.deleteProfileItem("note",name,note)));}
    JSONArray dates=x.optJSONArray("dates");for(int k=0;dates!=null&&k<dates.length();k++){String d=dates.optString(k);itemRow(c,s(R.string.card_date,d),14,GREEN,null,s(R.string.delete_item),()->confirmDerived(s(R.string.delete_item_title),d,()->store.deleteProfileItem("note",name,d)));}
   }}
  if((me==null||me.length()==0)&&(ps==null||ps.length()==0))empty(body,"document",s(R.string.card_empty),s(R.string.card_empty_help),null,null);
  add(body,ui.action(s(R.string.clear_derived),false,this::confirmClear),16,6);
 }
 String modelName(String id){if(id.isEmpty())return s(R.string.not_selected);String s=id.substring(id.lastIndexOf('/')+1);return s.isEmpty()?id:s;}
 void retention(){new AlertDialog.Builder(this).setTitle(s(R.string.local_retention)).setItems(new String[]{s(R.string.retention_forever),s(R.string.one_day),s(R.string.seven_days),s(R.string.thirty_days)},(d,n)->{try{store.configure(new JSONObject().put("retentionDays",new int[]{0,1,7,30}[n]));render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();}
 void diagnostics()throws Exception{JSONObject cfg=store.status();section(s(R.string.analysis_queue));LinearLayout queue=card(body);queue.addView(ui.bold(cfg.getBoolean("analysisEnabled")?s(R.string.analysis_on):s(R.string.analysis_off),20,INK));add(queue,ui.text(s(R.string.queue_details,cfg.getInt("analysisQueued"),cfg.getInt("analysisInFlight"),cfg.getLong("analysisBatchAttempts"),cfg.getInt("lastBatchSize")),14,MUTED),12,12);rule(queue);add(queue,ui.text(s(R.string.last_status,MemoryText.status(this,store.prefs.getString("analysisLastStatus",""))),12,MUTED),14,0);section(s(R.string.processing_policy));LinearLayout policy=card(body);policy.addView(ui.text(s(R.string.queue_policy),14,MUTED));section(s(R.string.connections));LinearLayout service=card(body);service.addView(ui.text(s(R.string.connection_details,cfg.getBoolean("observationConnected")?s(R.string.connected):s(R.string.disconnected),cfg.getString("model").isEmpty()?s(R.string.not_selected):cfg.getString("model"),MemoryText.status(this,store.prefs.getString("surfaceStatus","disconnected"))),12,MUTED));}
 void sources(){try{
  List<ResolveInfo> all=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);TreeMap<String,String> apps=new TreeMap<>();for(ResolveInfo r:all){String p=r.activityInfo.packageName;if(!p.equals(getPackageName())&&!MemoryStore.NEVER_OBSERVED.contains(p))apps.put(p,r.loadLabel(getPackageManager()).toString());}
  Set<String> current=store.prefs.getStringSet("sources",Set.of());for(String p:current)if(!MemoryStore.NEVER_OBSERVED.contains(p))apps.putIfAbsent(p,source(p));String[] pkgs=apps.keySet().toArray(new String[0]),labels=new String[pkgs.length];boolean[] selected=new boolean[pkgs.length];for(int i=0;i<pkgs.length;i++){labels[i]=apps.get(pkgs[i]);selected[i]=current.contains(pkgs[i]);}
  new AlertDialog.Builder(this).setTitle(s(R.string.capture_apps_title)).setMultiChoiceItems(labels,selected,(d,i,on)->selected[i]=on).setPositiveButton(s(R.string.save),(d,w)->{try{JSONArray result=new JSONArray();for(int i=0;i<pkgs.length;i++)if(selected[i])result.put(pkgs[i]);store.configure(new JSONObject().put("sources",result));render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();
 }catch(Exception e){message(e.getMessage());}}
 void systemAi(){try{startActivity(new Intent("org.agentos.action.AI_SETTINGS").setPackage("org.agentos.assistant"));}catch(Exception e){message(s(R.string.service_unavailable));}}
 void refreshSystemModel(){async(()->BrokerClient.call("systemModel",new JSONObject().put("model",store.followsSystem()?"":store.prefs.getString("model",""))),r->{try{store.syncSystemModel(r);if(tab.equals("settings"))render();}catch(Exception e){message(s(R.string.service_unavailable));}});}
 void models(){async(()->BrokerClient.call("models",null),r->{try{
  JSONArray a=r.getJSONArray("models");String[] labels=new String[a.length()+1];labels[0]=s(R.string.follow_system_model,modelName(r.optString("systemModel")));for(int i=0;i<a.length();i++)labels[i+1]=a.getJSONObject(i).getString("name");
  new AlertDialog.Builder(this).setTitle(s(R.string.choose_model)).setItems(labels,(d,n)->{try{store.configure(new JSONObject().put("model",n==0?"":a.getJSONObject(n-1).getString("id")).put("followSystem",n==0));refreshSystemModel();render();}catch(Exception e){message(e.getMessage());}}).setNegativeButton(s(R.string.cancel),null).show();
 }catch(Exception e){message(s(R.string.catalog_invalid));}});}
}
